/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.streaming.pubsub

import java.io.{Externalizable, ObjectInput, ObjectOutput}
import java.lang
import java.lang.Runtime
import java.util.concurrent.{Executors, TimeUnit}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.pubsub.Pubsub.Builder
import com.google.api.services.pubsub.model.{AcknowledgeRequest, PubsubMessage, PullRequest, ReceivedMessage, Subscription}
import com.google.cloud.hadoop.util.RetryHttpInitializer
import com.google.common.util.concurrent.RateLimiter

import org.apache.spark.SparkConf
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.dstream.ReceiverInputDStream
import org.apache.spark.streaming.receiver.Receiver
import org.apache.spark.util.Utils

/**
 * Input stream that subscribe messages from Google cloud Pub/Sub subscription.
 * @param project         Google cloud project id
 * @param topic           Topic name for creating subscription if need
 * @param subscription    Pub/Sub subscription name
 * @param credential      Google cloud project credential to access Pub/Sub service
 */
private[streaming]
class PubsubInputDStream(
    _ssc: StreamingContext,
    val project: String,
    val topic: Option[String],
    val subscription: String,
    val credential: SparkGCPCredentials,
    val _storageLevel: StorageLevel,
    val autoAcknowledge: Boolean,
    val maxNoOfMessageInRequest: Int,
    conf: SparkConf
) extends ReceiverInputDStream[SparkPubsubMessage](_ssc) {

  override def getReceiver(): Receiver[SparkPubsubMessage] = {
    new PubsubReceiver(
      project, topic, subscription, credential, _storageLevel, autoAcknowledge,
      maxNoOfMessageInRequest, conf
    )
  }
}

/**
 * A wrapper class for PubsubMessage's with a custom serialization format.
 *
 * This is necessary because PubsubMessage uses inner data structures
 * which are not serializable.
 */
class SparkPubsubMessage() extends Externalizable {

  private[pubsub] var message = new PubsubMessage
  private[pubsub] var ackId: String = _

  def getData(): Array[Byte] = message.decodeData()

  def getAttributes(): java.util.Map[String, String] = message.getAttributes

  def getMessageId(): String = message.getMessageId

  def getAckId(): String = ackId

  def getPublishTime(): String = message.getPublishTime

  override def writeExternal(out: ObjectOutput): Unit = Utils.tryOrIOException {
    message.decodeData() match {
      case null => out.writeInt(-1)
      case data =>
        out.writeInt(data.size)
        out.write(data)
    }

    message.getMessageId match {
      case null => out.writeInt(-1)
      case id =>
        val idBuff = Utils.serialize(id)
        out.writeInt(idBuff.length)
        out.write(idBuff)
    }

    ackId match {
      case null => out.writeInt(-1)
      case id =>
        val ackIdBuff = Utils.serialize(ackId)
        out.writeInt(ackIdBuff.length)
        out.write(ackIdBuff)
    }

    message.getPublishTime match {
      case null => out.writeInt(-1)
      case time =>
        val publishTimeBuff = Utils.serialize(time)
        out.writeInt(publishTimeBuff.length)
        out.write(publishTimeBuff)
    }

    message.getAttributes match {
      case null => out.writeInt(-1)
      case attrs =>
        out.writeInt(attrs.size())
        for ((k, v) <- message.getAttributes.asScala) {
          val keyBuff = Utils.serialize(k)
          out.writeInt(keyBuff.length)
          out.write(keyBuff)
          val valBuff = Utils.serialize(v)
          out.writeInt(valBuff.length)
          out.write(valBuff)
        }
    }
  }

  override def readExternal(in: ObjectInput): Unit = Utils.tryOrIOException {
    in.readInt() match {
      case -1 => message.encodeData(null)
      case bodyLength =>
        val data = new Array[Byte](bodyLength)
        in.readFully(data)
        message.encodeData(data)
    }

    in.readInt() match {
      case -1 => message.setMessageId(null)
      case idLength =>
        val idBuff = new Array[Byte](idLength)
        in.readFully(idBuff)
        val id: String = Utils.deserialize(idBuff)
        message.setMessageId(id)
    }

    in.readInt() match {
      case -1 => ackId = null
      case ackIdLength =>
        val ackIdBuff = new Array[Byte](ackIdLength)
        in.readFully(ackIdBuff)
        ackId = Utils.deserialize(ackIdBuff)
    }

    in.readInt() match {
      case -1 => message.setPublishTime(null)
      case publishTimeLength =>
        val publishTimeBuff = new Array[Byte](publishTimeLength)
        in.readFully(publishTimeBuff)
        val publishTime: String = Utils.deserialize(publishTimeBuff)
        message.setPublishTime(publishTime)
    }

    in.readInt() match {
      case -1 => message.setAttributes(null)
      case numAttributes =>
        val attributes = new java.util.HashMap[String, String]
        for (i <- 0 until numAttributes) {
          val keyLength = in.readInt()
          val keyBuff = new Array[Byte](keyLength)
          in.readFully(keyBuff)
          val key: String = Utils.deserialize(keyBuff)

          val valLength = in.readInt()
          val valBuff = new Array[Byte](valLength)
          in.readFully(valBuff)
          val value: String = Utils.deserialize(valBuff)

          attributes.put(key, value)
        }
        message.setAttributes(attributes)
    }
  }
}

private [pubsub]
object ConnectionUtils {
  val transport = GoogleNetHttpTransport.newTrustedTransport();
  val jacksonFactory = JacksonFactory.getDefaultInstance;

  // The topic or subscription already exists.
  // This is an error on creation operations.
  val ALREADY_EXISTS = 409

  /**
   * Client can retry with these response status
   */
  val RESOURCE_EXHAUSTED = 429

  val CANCELLED = 499

  val INTERNAL = 500

  val UNAVAILABLE = 503

  val DEADLINE_EXCEEDED = 504

  def retryable(status: Int): Boolean = {
    status match {
      case RESOURCE_EXHAUSTED | CANCELLED | INTERNAL | UNAVAILABLE | DEADLINE_EXCEEDED => true
      case _ => false
    }
  }
}

/**
 * Custom spark receiver to pull messages from Pubsub topic and push into reliable store.
 * If backpressure is enabled,the message ingestion rate for this receiver will be managed by Spark.
 *
 * Following spark configurations can be used to control rates and block size
 * <i>spark.streaming.backpressure.initialRate</i>
 * <i>spark.streaming.receiver.maxRate</i>
 * <i>spark.streaming.blockQueueSize</i>: Controlling block size
 * <i>spark.streaming.backpressure.pid.minRate</i>
 *
 * See Spark streaming configurations doc
 * <a href="https://spark.apache.org/docs/latest/configuration.html#spark-streaming</a>
 *
 * @param project                   Google cloud project id
 * @param topic                     Topic name for creating subscription if need
 * @param subscription              Pub/Sub subscription name
 * @param credential                Google cloud project credential to access Pub/Sub service
 * @param storageLevel              Storage level to be used
 * @param autoAcknowledge           Acknowledge pubsub message or not
 * @param maxNoOfMessageInRequest   Maximum number of message in a Pubsub pull request
 * @param conf                      Spark config
 */
private[pubsub]
class PubsubReceiver(
    project: String,
    topic: Option[String],
    subscription: String,
    credential: SparkGCPCredentials,
    storageLevel: StorageLevel,
    autoAcknowledge: Boolean,
    maxNoOfMessageInRequest: Int,
    conf: SparkConf)
    extends Receiver[SparkPubsubMessage](storageLevel) {

  val APP_NAME = "sparkstreaming-pubsub-receiver"

  val INIT_BACKOFF = 100 // 100ms

  val MAX_BACKOFF = 10 * 1000 // 10s

  val maxRateLimit: Long = conf.getLong("spark.streaming.receiver.maxRate", Long.MaxValue)

  val blockSize: Int = conf.getInt("spark.streaming.blockQueueSize", maxNoOfMessageInRequest)


  lazy val rateLimiter: RateLimiter = RateLimiter.create(getInitialRateLimit.toDouble)

  lazy val client = new Builder(
    ConnectionUtils.transport,
    ConnectionUtils.jacksonFactory,
    new RetryHttpInitializer(credential.provider, APP_NAME))
      .setApplicationName(APP_NAME)
      .build()

  val projectFullName: String = s"projects/$project"
  val subscriptionFullName: String = s"$projectFullName/subscriptions/$subscription"

  override def onStart(): Unit = {
    topic match {
      case Some(t) =>
        val sub: Subscription = new Subscription
        sub.setTopic(s"$projectFullName/topics/$t")
        try {
          client.projects().subscriptions().create(subscriptionFullName, sub).execute()
        } catch {
          case e: GoogleJsonResponseException =>
            if (e.getDetails.getCode == ConnectionUtils.ALREADY_EXISTS) {
              // Ignore subscription already exists exception.
            } else {
              reportError("Failed to create subscription", e)
            }
          case NonFatal(e) =>
            reportError("Failed to create subscription", e)
        }
      case None => // do nothing
    }

    new Thread() {
      override def run() {
        receive()
      }
    }.start()
  }

  def receive(): Unit = {
    val pullRequest = new PullRequest()
      .setMaxMessages(maxNoOfMessageInRequest).setReturnImmediately(false)
    var backoff = INIT_BACKOFF
    while (!isStopped()) {
      try {
        val pullResponse =
          client.projects().subscriptions().pull(subscriptionFullName, pullRequest).execute()
        val receivedMessages = pullResponse.getReceivedMessages

        // update rate limit if required
        updateRateLimit()

        if (receivedMessages != null) {
          pushToStoreAndAck(receivedMessages.asScala.toList)
        }
        backoff = INIT_BACKOFF
      } catch {
        case e: GoogleJsonResponseException =>
          if (ConnectionUtils.retryable(e.getDetails.getCode)) {
            Thread.sleep(backoff)
            backoff = Math.min(backoff * 2, MAX_BACKOFF)
          } else {
            reportError("Failed to pull messages", e)
          }
        case NonFatal(e) => reportError("Failed to pull messages", e)
      }
    }
  }

  def getInitialRateLimit: Long = {
    math.min(
      conf.getLong("spark.streaming.backpressure.initialRate", maxRateLimit),
      maxRateLimit
    )
  }

  /**
   * Get the new recommended rate at which receiver should push data into store
   * and update the rate limiter with new rate
   */
  def updateRateLimit(): Unit = {
    val newRateLimit = supervisor.getCurrentRateLimit.min(maxRateLimit)
    if (rateLimiter.getRate != newRateLimit) {
      rateLimiter.setRate(newRateLimit)
    }
  }

  /**
   * Push the list of received message into store and ack messages if auto ack is true
   * @param receivedMessages
   */
  def pushToStoreAndAck(receivedMessages: List[ReceivedMessage]): Unit = {
    receivedMessages
      .map(x => {
        val sm = new SparkPubsubMessage
        sm.message = x.getMessage
        sm.ackId = x.getAckId
        sm})
      .grouped(blockSize)
      .foreach(messages => {
        rateLimiter.acquire(messages.size)
        store(messages.toIterator)
        if (autoAcknowledge) acknowledgeIds(messages.map(_.ackId))
      })
  }

  /**
   * Acknowledge Message ackIds
   * @param ackIds
   */
  def acknowledgeIds(ackIds: List[String]): Unit = {
    val ackRequest = new AcknowledgeRequest()
    ackRequest.setAckIds(ackIds.asJava)
    client.projects().subscriptions()
      .acknowledge(subscriptionFullName, ackRequest).execute()
  }

  override def onStop(): Unit = {}
}
