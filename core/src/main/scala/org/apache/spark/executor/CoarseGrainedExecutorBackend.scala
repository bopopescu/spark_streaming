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

package org.apache.spark.executor

import java.net.URL
import java.nio.ByteBuffer

import scala.collection.mutable
import scala.concurrent.Await

import akka.actor.{Actor, ActorSelection, Props}
import akka.pattern.Patterns
import akka.remote.{RemotingLifecycleEvent, DisassociatedEvent}

import org.apache.spark.{Logging, SecurityManager, SparkConf, SparkEnv}
import org.apache.spark.TaskState.TaskState
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.deploy.worker.WorkerWatcher
import org.apache.spark.scheduler.TaskDescription
import org.apache.spark.scheduler.cluster.CoarseGrainedClusterMessages._
import org.apache.spark.util.{ActorLogReceive, AkkaUtils, SignalLogger, Utils}

import org.apache.spark.monitor.WorkerMonitorMessages._

private[spark] class CoarseGrainedExecutorBackend(
    driverUrl: String,
    executorId: String,
    hostPort: String,
    cores: Int,
    userClassPath: Seq[URL],
    env: SparkEnv,
    workerMonitorUrl: Option[String])
  extends Actor with ActorLogReceive with ExecutorBackend with Logging {

  Utils.checkHostPort(hostPort, "Expected hostport")

  var executor: Executor = null
  var driver: ActorSelection = null
  var workerMonitor: ActorSelection = null

  var totalSpeed = 0.0

  override def preStart() {
    logInfo("Connecting to driver: " + driverUrl)
    driver = context.actorSelection(driverUrl)
    driver ! RegisterExecutor(executorId, hostPort, cores, extractLogUrls)
    context.system.eventStream.subscribe(self, classOf[RemotingLifecycleEvent])

    logInfo(s"workermonitor url = $workerMonitorUrl")
    workerMonitorUrl match {
      case Some(url) =>
        workerMonitor = context.actorSelection(url)
        logInfo("Connecting to worker monitor: " + workerMonitorUrl)
        workerMonitor ! RegisterExecutorInWorkerMonitor(executorId)

        driver ! RegisterWorkerMonitorInSchedulerBackend(url)

      case None =>
    }

  }

  def extractLogUrls: Map[String, String] = {
    val prefix = "SPARK_LOG_URL_"
    sys.env.filterKeys(_.startsWith(prefix))
      .map(e => (e._1.substring(prefix.length).toLowerCase, e._2))
  }

  override def receiveWithLogging = {
    case RegisteredExecutorInWorkerMonitor =>
      logInfo("Successfully registered with WorkerMonitor")

//    case HandledDataSpeed =>
//      logInfo("Get data size which has been handled by executor")
//      workerMonitor ! ExecutorHandledDataSpeed(executor.requireHandledDataSpeed, executorId)

    case RegisteredExecutor =>
      logInfo("Successfully registered with driver")
      val (hostname, _) = Utils.parseHostPort(hostPort)
      executor = new Executor(executorId, hostname, env, userClassPath, isLocal = false)

    case RegisterExecutorFailed(message) =>
      logError("Slave registration failed: " + message)
      System.exit(1)

    case LaunchTask(data) =>
      if (executor == null) {
        logError("Received LaunchTask command but executor was null")
        System.exit(1)
      } else {
        val ser = env.closureSerializer.newInstance()
        val taskDesc = ser.deserialize[TaskDescription](data.value)
        logInfo("Got assigned task " + taskDesc.taskId)
        executor.launchTask(this, taskId = taskDesc.taskId, attemptNumber = taskDesc.attemptNumber,
          taskDesc.name, taskDesc.serializedTask)
      }

    case KillTask(taskId, _, interruptThread) =>
      if (executor == null) {
        logError("Received KillTask command but executor was null")
        System.exit(1)
      } else {
        executor.killTask(taskId, interruptThread)
      }

    case x: DisassociatedEvent =>
      if (x.remoteAddress == driver.anchorPath.address) {
        logError(s"Driver $x disassociated! Shutting down.")

        if ((workerMonitor != null) && (!executor.excutorIsStoped)) {
          workerMonitor ! StoppedExecutor(executorId)
          totalSpeed = 0.0
        }

        System.exit(1)
      } else {
        logWarning(s"Received irrelevant DisassociatedEvent $x")
      }

    case StopExecutor =>
      logInfo("Driver commanded a shutdown")
      executor.stop()
      context.stop(self)
      context.system.shutdown()
      if (workerMonitor != null) {
        workerMonitor ! StoppedExecutor(executorId)
        totalSpeed = 0.0
      }
  }

  override def statusUpdate(taskId: Long, state: TaskState, data: ByteBuffer) {
    driver ! StatusUpdate(executorId, taskId, state, data)
  }

  override def handledDataUpdate(taskId: Long, startTime: Long, endTime: Long, dataSize: Long): Unit = {
    val currentHandledSpeed = (dataSize / (endTime - startTime)).abs
    totalSpeed = if (totalSpeed == 0) currentHandledSpeed else totalSpeed * 0.4 + currentHandledSpeed * 0.6
    logInfo(s"executor ${executorId} current handled speed ${currentHandledSpeed}, total handled speed ${totalSpeed}")
    workerMonitor ! ExecutorHandledDataSpeed(dataSize, totalSpeed, executorId)
  }
}

private[spark] object CoarseGrainedExecutorBackend extends Logging {

  private def run(
      driverUrl: String,
      executorId: String,
      hostname: String,
      cores: Int,
      appId: String,
      workerUrl: Option[String],
      workerMonitorUrl: Option[String],
      userClassPath: Seq[URL]) {

    SignalLogger.register(log)

    SparkHadoopUtil.get.runAsSparkUser { () =>
      // Debug code
      Utils.checkHost(hostname)

      // Bootstrap to fetch the driver's Spark properties.
      val executorConf = new SparkConf
      val port = executorConf.getInt("spark.executor.port", 0)
      val (fetcher, _) = AkkaUtils.createActorSystem(
        "driverPropsFetcher",
        hostname,
        port,
        executorConf,
        new SecurityManager(executorConf))
      val driver = fetcher.actorSelection(driverUrl)
      val timeout = AkkaUtils.askTimeout(executorConf)
      val fut = Patterns.ask(driver, RetrieveSparkProps, timeout)
      val props = Await.result(fut, timeout).asInstanceOf[Seq[(String, String)]] ++
        Seq[(String, String)](("spark.app.id", appId))
      fetcher.shutdown()

      // Create SparkEnv using properties we fetched from the driver.
      val driverConf = new SparkConf()
      for ((key, value) <- props) {
        // this is required for SSL in standalone mode
        if (SparkConf.isExecutorStartupConf(key)) {
          driverConf.setIfMissing(key, value)
        } else {
          driverConf.set(key, value)
        }
      }
      val env = SparkEnv.createExecutorEnv(
        driverConf, executorId, hostname, port, cores, isLocal = false)

      // SparkEnv sets spark.driver.port so it shouldn't be 0 anymore.
      val boundPort = env.conf.getInt("spark.executor.port", 0)
      assert(boundPort != 0)

      // Start the CoarseGrainedExecutorBackend actor.
      val sparkHostPort = hostname + ":" + boundPort

//      env.actorSystem.actorOf(
//        Props(classOf[CoarseGrainedExecutorBackend],
//          driverUrl, executorId, sparkHostPort, cores, userClassPath, env),
//        name = "Executor")

      // add a workerMonitorUrl to the executor, so it can sent the message to the workerMonitor.
      env.actorSystem.actorOf(
        Props(classOf[CoarseGrainedExecutorBackend],
          driverUrl, executorId, sparkHostPort, cores, userClassPath, env, workerMonitorUrl),
        name = "Executor")

      workerUrl.foreach { url =>
        env.actorSystem.actorOf(Props(classOf[WorkerWatcher], url), name = "WorkerWatcher")
      }

      env.actorSystem.awaitTermination()
    }
  }

  def main(args: Array[String]) {
    var driverUrl: String = null
    var executorId: String = null
    var hostname: String = null
    var cores: Int = 0
    var appId: String = null
    var workerUrl: Option[String] = None
    var workerMonitorUrl: Option[String] = None
    val userClassPath = new mutable.ListBuffer[URL]()

    var argv = args.toList
    while (!argv.isEmpty) {
      argv match {
        case ("--driver-url") :: value :: tail =>
          driverUrl = value
          argv = tail
        case ("--executor-id") :: value :: tail =>
          executorId = value
          argv = tail
        case ("--hostname") :: value :: tail =>
          hostname = value
          argv = tail
        case ("--cores") :: value :: tail =>
          cores = value.toInt
          argv = tail
        case ("--app-id") :: value :: tail =>
          appId = value
          argv = tail
        case ("--worker-url") :: value :: tail =>
          // Worker url is used in spark standalone mode to enforce fate-sharing with worker
          workerUrl = Some(value)
          argv = tail
        case ("--worker-monitor-url") :: value :: tail =>
          workerMonitorUrl = Some(value)
          argv = tail
        case ("--user-class-path") :: value :: tail =>
          userClassPath += new URL(value)
          argv = tail
        case Nil =>
        case tail =>
          System.err.println(s"Unrecognized options: ${tail.mkString(" ")}")
          printUsageAndExit()
      }
    }

    if (driverUrl == null || executorId == null || hostname == null || cores <= 0 ||
      appId == null) {
      printUsageAndExit()
    }

    run(driverUrl, executorId, hostname, cores, appId, workerUrl, workerMonitorUrl, userClassPath)
  }

  private def printUsageAndExit() = {
    System.err.println(
      """
      |"Usage: CoarseGrainedExecutorBackend [options]
      |
      | Options are:
      |   --driver-url <driverUrl>
      |   --executor-id <executorId>
      |   --hostname <hostname>
      |   --cores <cores>
      |   --app-id <appid>
      |   --worker-url <workerUrl>
      |   --user-class-path <url>
      |""".stripMargin)
    System.exit(1)
  }

}
