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

package org.apache.spark.streaming

import java.io.File

import org.scalatest.BeforeAndAfter

import org.apache.spark.{SparkFunSuite, Logging}
import org.apache.spark.util.Utils

/**
 * This testsuite tests master failures at random times while the stream is running using
 * the real clock.
 * 这个测试套件主节点随机故障在流运行时使用实时
 */
class FailureSuite extends SparkFunSuite with BeforeAndAfter with Logging {

  private val batchDuration: Duration = Milliseconds(1000)
  private val numBatches = 30
  private var directory: File = null

  before {
    directory = Utils.createTempDir()
  }

  after {
    if (directory != null) {
     //删除临时目录
      Utils.deleteRecursively(directory)
    }
    //停止所有活动实时流
    StreamingContext.getActive().foreach { _.stop() }
  }
  //多次失败map
  test("multiple failures with map") {
    MasterFailureTest.testMap(directory.getAbsolutePath, numBatches, batchDuration)
  }
  //多次失败updateStateByKey
  test("multiple failures with updateStateByKey") {
    MasterFailureTest.testUpdateStateByKey(directory.getAbsolutePath, numBatches, batchDuration)
  }
}

