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

package org.apache.spark.shuffle.hash

import java.io.IOException

import org.apache.spark._
import org.apache.spark.executor.ShuffleWriteMetrics
import org.apache.spark.scheduler.MapStatus
import org.apache.spark.serializer.Serializer
import org.apache.spark.shuffle._
import org.apache.spark.storage.DiskBlockObjectWriter

private[spark] class HashShuffleWriter[K, V](
    shuffleBlockResolver: FileShuffleBlockResolver,
    handle: BaseShuffleHandle[K, V, _],
    mapId: Int, //对应RDD的partionsID
    context: TaskContext)
  extends ShuffleWriter[K, V] with Logging {

  private val dep = handle.dependency
  private val numOutputSplits = dep.partitioner.numPartitions
  private val metrics = context.taskMetrics

  // Are we in the process of stopping? Because map tasks can call stop() with success = true
  // and then call stop() with success = false if they get an exception, we want to make sure
  // we don't try deleting files, etc twice.
  //我们正在停止吗?因为map任务可以使用success = true调用stop()
  // 然后在success = false的情况下调用stop()得到异常,我们想确保我们不尝试删除文件等两次。
  private var stopping = false

  private val writeMetrics = new ShuffleWriteMetrics()
  metrics.shuffleWriteMetrics = Some(writeMetrics)

  private val blockManager = SparkEnv.get.blockManager
  private val ser = Serializer.getSerializer(dep.serializer.getOrElse(null))
  //mapId对应RDD的partionsID
  private val shuffle = shuffleBlockResolver.forMapTask(dep.shuffleId, mapId, numOutputSplits, ser,
    writeMetrics)

  /**
    * Write a bunch of records to this task's output
    * 将一堆记录写入此任务的输出*/
    /**
     * 主要处理两件事:
     * 1)判断是否需要进行聚合,比如<hello,1>和<hello,1>都要写入的话,那么先生成<hello,2>
     *   然后再进行后续的写入工作
     * 2)利用Partition函数来决定<k,val>写入哪一个文件中.
     */
  override def write(records: Iterator[Product2[K, V]]): Unit = {
    //判断aggregator是否被定义,需要做Map端聚合操作
    val iter = if (dep.aggregator.isDefined) {
      if (dep.mapSideCombine) {//判断是否需要聚合,如果需要,聚合records执行map端的聚合
        //汇聚工作,reducebyKey是一分为二的,一部在ShuffleMapTask中进行聚合
        //另一部分在resultTask中聚合
        dep.aggregator.get.combineValuesByKey(records, context)
      } else {
        records
      }
    } else {
      require(!dep.mapSideCombine, "Map-side combine without Aggregator specified!")
      records
    }
     //利用getPartition函数来决定<k,val>写入哪一个文件中.
    for (elem <- iter) {
     //elem是类似于<k,val>的键值对,以K为参数用partitioner计算其对应的值,
      val bucketId = dep.partitioner.getPartition(elem._1)//获得该element需要写入的partitioner
      //实际调用FileShuffleBlockManager.forMapTask进入数据写入
      //bucketId文件名称,key elem._1,value elem._2
      shuffle.writers(bucketId).write(elem._1, elem._2)
    }
  }

  /** Close this writer, passing along whether the map completed
    * 关闭这位writer,传递map是否完成*/
  override def stop(initiallySuccess: Boolean): Option[MapStatus] = {
    var success = initiallySuccess
    try {
      if (stopping) {
        return None
      }
      stopping = true
      if (success) {
        try {
          Some(commitWritesAndBuildStatus())
        } catch {
          case e: Exception =>
            success = false
            revertWrites()
            throw e
        }
      } else {
        revertWrites()
        None
      }
    } finally {
      // Release the writers back to the shuffle block manager.
      //释放writers回到shuffle块管理器
      if (shuffle != null && shuffle.writers != null) {
        try {
          shuffle.releaseWriters(success)
        } catch {
          case e: Exception => logError("Failed to release shuffle writers", e)
        }
      }
    }
  }

  private def commitWritesAndBuildStatus(): MapStatus = {
    // Commit the writes. Get the size of each bucket block (total block size).
    //提交写,获取每个桶块的大小(总块大小)
    val sizes: Array[Long] = shuffle.writers.map { writer: DiskBlockObjectWriter =>
      writer.commitAndClose()
      writer.fileSegment().length
    }
    if (!shuffleBlockResolver.consolidateShuffleFiles) {
      // rename all shuffle files to final paths
      // Note: there is only one ShuffleBlockResolver in executor
      //将所有shuffle文件重命名为最终路径
      //注意:执行器中只有一个ShuffleBlockResolver
      shuffleBlockResolver.synchronized {
        shuffle.writers.zipWithIndex.foreach { case (writer, i) =>
          val output = blockManager.diskBlockManager.getFile(writer.blockId)
          if (sizes(i) > 0) {
            if (output.exists()) {
              // Use length of existing file and delete our own temporary one
              //使用现有文件的长度并删除我们自己的临时文件
              sizes(i) = output.length()
              writer.file.delete()
            } else {
              // Commit by renaming our temporary file to something the fetcher expects
              //将我们的临时文件重命名为提交者期望的内容
              if (!writer.file.renameTo(output)) {
                throw new IOException(s"fail to rename ${writer.file} to $output")
              }
            }
          } else {
            if (output.exists()) {
              output.delete()
            }
          }
        }
      }
    }
    MapStatus(blockManager.shuffleServerId, sizes)
  }

  private def revertWrites(): Unit = {
    if (shuffle != null && shuffle.writers != null) {
      for (writer <- shuffle.writers) {
        writer.revertPartialWritesAndClose()
      }
    }
  }
}
