/*
 * Copyright 2010 LinkedIn
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.consumer

import kafka.utils.IteratorTemplate
import org.apache.log4j.Logger
import java.util.concurrent.{TimeUnit, BlockingQueue}
import kafka.cluster.Partition
import kafka.message.{MessageOffset, MessageSet, Message}

/**
 * An iterator that blocks until a value can be read from the supplied queue.
 * The iterator takes a shutdownCommand object which can be added to the queue to trigger a shutdown
 * 
 */
class ConsumerIterator(private val channel: BlockingQueue[FetchedDataChunk], consumerTimeoutMs: Int)
        extends IteratorTemplate[Message] {
  
  private val logger = Logger.getLogger(classOf[ConsumerIterator])
  private var current: Iterator[MessageOffset] = null
  private var currentDataChunk: FetchedDataChunk = null
  private var setConsumedOffset: Boolean = false
  private var currentTopicInfo: PartitionTopicInfo = null

  override def next(): Message = {
    val message = super.next
    if(setConsumedOffset) {
      currentTopicInfo.consumed(currentDataChunk.messages.shallowValidBytes)
      setConsumedOffset = false
    }
    message
  }

  protected def makeNext(): Message = {
    // if we don't have an iterator, get one
    if(current == null || !current.hasNext) {
      if (consumerTimeoutMs < 0)
        currentDataChunk = channel.take
      else {
        currentDataChunk = channel.poll(consumerTimeoutMs, TimeUnit.MILLISECONDS)
        if (currentDataChunk == null) {
          throw new ConsumerTimeoutException
        }
      }
      if(currentDataChunk eq ZookeeperConsumerConnector.shutdownCommand) {
        logger.debug("Received the shutdown command")
    	  channel.offer(currentDataChunk)
        return allDone
      } else {
        currentTopicInfo = currentDataChunk.topicInfo
        if (currentTopicInfo.getConsumeOffset != currentDataChunk.fetchOffset) {
          logger.error("consumed offset: " + currentTopicInfo.getConsumeOffset + " doesn't match fetch offset: " +
            currentDataChunk.fetchOffset + " for " + currentTopicInfo + "; consumer may lose data")
          currentTopicInfo.resetConsumeOffset(currentDataChunk.fetchOffset)
        }
        current = currentDataChunk.messages.iterator
      }
    }
    val item = current.next
    if(!current.hasNext) {
      // the iterator in this data chunk is exhausted. Update the consumed offset now
      setConsumedOffset = true
    }

    item.message
  }
  
}

class ConsumerTimeoutException() extends RuntimeException()
