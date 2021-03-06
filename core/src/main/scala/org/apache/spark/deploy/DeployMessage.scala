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

package org.apache.spark.deploy

import scala.collection.immutable.List

import org.apache.spark.deploy.ExecutorState.ExecutorState
import org.apache.spark.deploy.master.{ApplicationInfo, DriverInfo, WorkerInfo}
import org.apache.spark.deploy.master.DriverState.DriverState
import org.apache.spark.deploy.master.RecoveryState.MasterState
import org.apache.spark.deploy.worker.{DriverRunner, ExecutorRunner}
import org.apache.spark.rpc.RpcEndpointRef
import org.apache.spark.util.Utils

private[deploy] sealed trait DeployMessage extends Serializable

/** 
 *  Contains messages sent between Scheduler endpoint nodes. 
 *  包含调度节点消息发送
 *  */
private[deploy] object DeployMessages {

  // Worker to Master
  // Work 注册到Master
  case class RegisterWorker(
      id: String,
      host: String,//主机
      port: Int,//端口
      worker: RpcEndpointRef,//RPC
      cores: Int,//内核数
      memory: Int,//内存数
      webUiPort: Int,//webUI端口
      publicAddress: String)
    extends DeployMessage {
    Utils.checkHost(host, "Required hostname")
    assert (port > 0)
  }

  case class ExecutorStateChanged(
      appId: String,
      execId: Int,
      state: ExecutorState,
      message: Option[String],
      exitStatus: Option[Int])
    extends DeployMessage

  case class DriverStateChanged(
      driverId: String,
      state: DriverState,
      exception: Option[Exception])
    extends DeployMessage

  case class WorkerSchedulerStateResponse(id: String, executors: List[ExecutorDescription],
     driverIds: Seq[String])

  case class Heartbeat(workerId: String, worker: RpcEndpointRef) extends DeployMessage

  // Master to Worker
  // Master 到 Worker
  case class RegisteredWorker(master: RpcEndpointRef, masterWebUiUrl: String) extends DeployMessage

  case class RegisterWorkerFailed(message: String) extends DeployMessage

  case class ReconnectWorker(masterUrl: String) extends DeployMessage

  case class KillExecutor(masterUrl: String, appId: String, execId: Int) extends DeployMessage

  case class LaunchExecutor(//启动Executor
      masterUrl: String,//
      appId: String,
      execId: Int,
      appDesc: ApplicationDescription,
      cores: Int,//内核
      memory: Int)//内存
    extends DeployMessage

  case class LaunchDriver(driverId: String, driverDesc: DriverDescription) extends DeployMessage

  case class KillDriver(driverId: String) extends DeployMessage

  case class ApplicationFinished(id: String)

  // Worker internal
  //定期发送到工作端清理应用程序文件夹
  case object WorkDirCleanup // Sent to Worker endpoint periodically(定期地) for cleaning up app folders
  //当一个Woker试图重新连接到一个Master
  case object ReregisterWithMaster // used when a worker attempts to reconnect to a master

  // AppClient to Master

  case class RegisterApplication(appDescription: ApplicationDescription, driver: RpcEndpointRef)
    extends DeployMessage

  case class UnregisterApplication(appId: String)

  case class MasterChangeAcknowledged(appId: String)

  case class RequestExecutors(appId: String, requestedTotal: Int)

  case class KillExecutors(appId: String, executorIds: Seq[String])

  // Master to AppClient

  case class RegisteredApplication(appId: String, master: RpcEndpointRef) extends DeployMessage

  // TODO(matei): replace hostPort with host
  case class ExecutorAdded(id: Int, workerId: String, hostPort: String, cores: Int, memory: Int) {
    Utils.checkHostPort(hostPort, "Required hostport")
  }

  case class ExecutorUpdated(id: Int, state: ExecutorState, message: Option[String],
    exitStatus: Option[Int])

  case class ApplicationRemoved(message: String)

  // DriverClient <-> Master

  case class RequestSubmitDriver(driverDescription: DriverDescription) extends DeployMessage

  case class SubmitDriverResponse(
      master: RpcEndpointRef, success: Boolean, driverId: Option[String], message: String)
    extends DeployMessage

  case class RequestKillDriver(driverId: String) extends DeployMessage

  case class KillDriverResponse(
      master: RpcEndpointRef, driverId: String, success: Boolean, message: String)
    extends DeployMessage

  case class RequestDriverStatus(driverId: String) extends DeployMessage

  case class DriverStatusResponse(found: Boolean, state: Option[DriverState],
    workerId: Option[String], workerHostPort: Option[String], exception: Option[Exception])

  // Internal message in AppClient AppClient中的内部消息

  case object StopAppClient

  // Master to Worker & AppClient

  case class MasterChanged(master: RpcEndpointRef, masterWebUiUrl: String)

  // MasterWebUI To Master

  case object RequestMasterState

  // Master to MasterWebUI

  case class MasterStateResponse(
      host: String,
      port: Int,
      restPort: Option[Int],
      workers: Array[WorkerInfo],
      activeApps: Array[ApplicationInfo],
      completedApps: Array[ApplicationInfo],
      activeDrivers: Array[DriverInfo],
      completedDrivers: Array[DriverInfo],
      status: MasterState) {

    Utils.checkHost(host, "Required hostname")
    assert (port > 0)

    def uri: String = "spark://" + host + ":" + port
    def restUri: Option[String] = restPort.map { p => "spark://" + host + ":" + p }
  }

  //  WorkerWebUI to Worker

  case object RequestWorkerState

  // Worker to WorkerWebUI
  //Worker 通过持有 ExecutorRunner 对象来控制 CoarseGrainedExecutorBackend 的启停
  case class WorkerStateResponse(host: String, port: Int, workerId: String,
    executors: List[ExecutorRunner], finishedExecutors: List[ExecutorRunner],
    drivers: List[DriverRunner], finishedDrivers: List[DriverRunner], masterUrl: String,
    cores: Int, memory: Int, coresUsed: Int, memoryUsed: Int, masterWebUiUrl: String) {

    Utils.checkHost(host, "Required hostname")
    assert (port > 0)
  }

  // Liveness checks in various places

  case object SendHeartbeat

}
