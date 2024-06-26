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
package org.apache.spark.sql.execution.streaming

import java.io.Serializable

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.execution.streaming.state._
import org.apache.spark.sql.streaming.TimeoutMode
import org.apache.spark.sql.types._
import org.apache.spark.util.NextIterator

/**
 * Singleton utils class used primarily while interacting with TimerState
 */
object TimerStateUtils {
  case class TimestampWithKey(
      key: Any,
      expiryTimestampMs: Long) extends Serializable

  val PROC_TIMERS_STATE_NAME = "_procTimers"
  val EVENT_TIMERS_STATE_NAME = "_eventTimers"
  val KEY_TO_TIMESTAMP_CF = "_keyToTimestamp"
  val TIMESTAMP_TO_KEY_CF = "_timestampToKey"
}

/**
 * Class that provides the implementation for storing timers
 * used within the `transformWithState` operator.
 * @param store - state store to be used for storing timer data
 * @param timeoutMode - mode of timeout (event time or processing time)
 * @param keyExprEnc - encoder for key expression
 */
class TimerStateImpl(
    store: StateStore,
    timeoutMode: TimeoutMode,
    keyExprEnc: ExpressionEncoder[Any]) extends Logging {

  private val EMPTY_ROW =
    UnsafeProjection.create(Array[DataType](NullType)).apply(InternalRow.apply(null))

  private val schemaForPrefixKey: StructType = new StructType()
    .add("key", BinaryType)

  private val schemaForKeyRow: StructType = new StructType()
    .add("key", BinaryType)
    .add("expiryTimestampMs", LongType, nullable = false)

  private val keySchemaForSecIndex: StructType = new StructType()
    .add("expiryTimestampMs", LongType, nullable = false)
    .add("key", BinaryType)

  private val schemaForValueRow: StructType =
    StructType(Array(StructField("__dummy__", NullType)))

  private val keySerializer = keyExprEnc.createSerializer()

  private val prefixKeyEncoder = UnsafeProjection.create(schemaForPrefixKey)

  private val keyEncoder = UnsafeProjection.create(schemaForKeyRow)

  private val secIndexKeyEncoder = UnsafeProjection.create(keySchemaForSecIndex)

  val timerCFName = if (timeoutMode == TimeoutMode.ProcessingTime) {
    TimerStateUtils.PROC_TIMERS_STATE_NAME
  } else {
    TimerStateUtils.EVENT_TIMERS_STATE_NAME
  }

  val keyToTsCFName = timerCFName + TimerStateUtils.KEY_TO_TIMESTAMP_CF
  store.createColFamilyIfAbsent(keyToTsCFName, schemaForKeyRow,
    schemaForValueRow, PrefixKeyScanStateEncoderSpec(schemaForKeyRow, 1),
    useMultipleValuesPerKey = false, isInternal = true)

  val tsToKeyCFName = timerCFName + TimerStateUtils.TIMESTAMP_TO_KEY_CF
  store.createColFamilyIfAbsent(tsToKeyCFName, keySchemaForSecIndex,
    schemaForValueRow, NoPrefixKeyStateEncoderSpec(keySchemaForSecIndex),
    useMultipleValuesPerKey = false, isInternal = true)

  private def getGroupingKey(cfName: String): Any = {
    val keyOption = ImplicitGroupingKeyTracker.getImplicitKeyOption
    if (!keyOption.isDefined) {
      throw StateStoreErrors.implicitKeyNotFound(cfName)
    }
    keyOption.get
  }

  private def encodeKey(groupingKey: Any, expiryTimestampMs: Long): UnsafeRow = {
    val keyByteArr = keySerializer.apply(groupingKey).asInstanceOf[UnsafeRow].getBytes()
    val keyRow = keyEncoder(InternalRow(keyByteArr, expiryTimestampMs))
    keyRow
  }

  // We maintain a secondary index that inverts the ordering of the timestamp
  // and grouping key
  // TODO: use range scan encoder to encode the secondary index key
  private def encodeSecIndexKey(groupingKey: Any, expiryTimestampMs: Long): UnsafeRow = {
    val keyByteArr = keySerializer.apply(groupingKey).asInstanceOf[UnsafeRow].getBytes()
    val keyRow = secIndexKeyEncoder(InternalRow(expiryTimestampMs, keyByteArr))
    keyRow
  }

  /**
   * Function to check if the timer for the given key and timestamp is already registered
   * @param expiryTimestampMs - expiry timestamp of the timer
   * @return - true if the timer is already registered, false otherwise
   */
  private def exists(groupingKey: Any, expiryTimestampMs: Long): Boolean = {
    getImpl(groupingKey, expiryTimestampMs) != null
  }

  private def getImpl(groupingKey: Any, expiryTimestampMs: Long): UnsafeRow = {
    store.get(encodeKey(groupingKey, expiryTimestampMs), keyToTsCFName)
  }

  /**
   * Function to add a new timer for the given key and timestamp
   * @param expiryTimestampMs - expiry timestamp of the timer
   */
  def registerTimer(expiryTimestampMs: Long): Unit = {
    val groupingKey = getGroupingKey(keyToTsCFName)
    if (exists(groupingKey, expiryTimestampMs)) {
      logWarning(s"Failed to register timer for key=$groupingKey and " +
        s"timestamp=$expiryTimestampMs since it already exists")
    } else {
      store.put(encodeKey(groupingKey, expiryTimestampMs), EMPTY_ROW, keyToTsCFName)
      store.put(encodeSecIndexKey(groupingKey, expiryTimestampMs), EMPTY_ROW, tsToKeyCFName)
      logDebug(s"Registered timer for key=$groupingKey and timestamp=$expiryTimestampMs")
    }
  }

  /**
   * Function to remove the timer for the given key and timestamp
   * @param expiryTimestampMs - expiry timestamp of the timer
   */
  def deleteTimer(expiryTimestampMs: Long): Unit = {
    val groupingKey = getGroupingKey(keyToTsCFName)

    if (!exists(groupingKey, expiryTimestampMs)) {
      logWarning(s"Failed to delete timer for key=$groupingKey and " +
        s"timestamp=$expiryTimestampMs since it does not exist")
    } else {
      store.remove(encodeKey(groupingKey, expiryTimestampMs), keyToTsCFName)
      store.remove(encodeSecIndexKey(groupingKey, expiryTimestampMs), tsToKeyCFName)
      logDebug(s"Deleted timer for key=$groupingKey and timestamp=$expiryTimestampMs")
    }
  }

  def listTimers(): Iterator[Long] = {
    val keyByteArr = keySerializer.apply(getGroupingKey(keyToTsCFName))
      .asInstanceOf[UnsafeRow].getBytes()
    val keyRow = prefixKeyEncoder(InternalRow(keyByteArr))
    val iter = store.prefixScan(keyRow, keyToTsCFName)
    iter.map { kv =>
      val keyRow = kv.key
      keyRow.getLong(1)
    }
  }

  private def getTimerRowFromSecIndex(keyRow: UnsafeRow): (Any, Long) = {
    // Decode the key object from the UnsafeRow
    val keyBytes = keyRow.getBinary(1)
    val retUnsafeRow = new UnsafeRow(1)
    retUnsafeRow.pointTo(keyBytes, keyBytes.length)
    val keyObj = keyExprEnc.resolveAndBind()
      .createDeserializer().apply(retUnsafeRow).asInstanceOf[Any]

    val expiryTimestampMs = keyRow.getLong(0)
    (keyObj, expiryTimestampMs)
  }

  /**
   * Function to get all the registered timers for all grouping keys
   * @return - iterator of all the registered timers for all grouping keys
   */
  def getExpiredTimers(): Iterator[(Any, Long)] = {
    val iter = store.iterator(tsToKeyCFName)

    new NextIterator[(Any, Long)] {
      override protected def getNext(): (Any, Long) = {
        if (iter.hasNext) {
          val rowPair = iter.next()
          val keyRow = rowPair.key
          val result = getTimerRowFromSecIndex(keyRow)
          result
        } else {
          finished = true
          null.asInstanceOf[(Any, Long)]
        }
      }

      override protected def close(): Unit = { }
    }
  }
}
