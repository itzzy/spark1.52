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
package org.apache.spark.deploy.history

import java.io.{File, FileInputStream, FileWriter, InputStream, IOException}
import java.net.{HttpURLConnection, URL}
import java.util.zip.ZipInputStream
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import com.google.common.base.Charsets
import com.google.common.io.{ByteStreams, Files}
import org.apache.commons.io.{FileUtils, IOUtils}
import org.mockito.Mockito.when
import org.scalatest.{BeforeAndAfter, Matchers}
import org.scalatest.mock.MockitoSugar

import org.apache.spark.{JsonTestUtils, SecurityManager, SparkConf, SparkFunSuite}
import org.apache.spark.ui.SparkUI

/**
 * A collection of tests against the historyserver, including comparing responses from the json
 * metrics api to a set of known "golden files".  If new endpoints / parameters are added,
 * cases should be added to this test suite.  The expected outcomes can be genered by running
 * the HistoryServerSuite.main.  Note that this will blindly generate new expectation files matching
 * the current behavior -- the developer must verify that behavior is correct.
  *
  * 针对历史服务器的一系列测试,包括将来自json metrics api的响应与一组已知的“黄金文件”进行比较。
  * 如果添加了新的端点/参数.则应将案例添加到此测试套件中.可以通过运行HistoryServerSuite.main来预测结果,
  * 请注意,这将盲目生成与当前行为匹配的新期望文件 - 开发人员必须验证该行为是否正确。
 *
 * Similarly, if the behavior is changed, HistoryServerSuite.main can be run to update the
 * 同样地,如果行为发生了改变,可以运行historyserversuite.main更新的期望值,然而,在一般来说,这应该是极端谨慎
 * expectations.  However, in general this should be done with extreme caution, as the metrics
 * are considered part of Spark's public api.
  * 同样,如果行为发生变化,可以运行HistoryServerSuite.main来更新期望,然而,一般来说,这应该非常小心,
  * 因为这些指标被认为是Spark公共api的一部分
 */
class HistoryServerSuite extends SparkFunSuite with BeforeAndAfter with Matchers with MockitoSugar
  with JsonTestUtils {

  private val logDir = new File("src/test/resources/spark-events")
  private val expRoot = new File("src/test/resources/HistoryServerExpectations/")

  private var provider: FsHistoryProvider = null
  private var server: HistoryServer = null
  private var port: Int = -1

  def init(): Unit = {
    val conf = new SparkConf()
      .set("spark.history.fs.logDirectory", logDir.getAbsolutePath)
      .set("spark.history.fs.updateInterval", "0")
      .set("spark.testing", "true")
    provider = new FsHistoryProvider(conf)
    provider.checkForLogs()
    val securityManager = new SecurityManager(conf)

    server = new HistoryServer(conf, provider, securityManager, 18080)
    server.initialize()
    server.bind()
    port = server.boundPort
  }

  def stop(): Unit = {
    server.stop()
  }

  before {
    init()
  }

  after{
    stop()
  }

  val cases = Seq(
    "application list json" -> "applications",
    "completed app list json" -> "applications?status=completed",
    "running app list json" -> "applications?status=running",
    "minDate app list json" -> "applications?minDate=2015-02-10",
    "maxDate app list json" -> "applications?maxDate=2015-02-10",
    "maxDate2 app list json" -> "applications?maxDate=2015-02-03T16:42:40.000GMT",
    "one app json" -> "applications/local-1422981780767",
    "one app multi-attempt json" -> "applications/local-1426533911241",
    "job list json" -> "applications/local-1422981780767/jobs",
    "job list from multi-attempt app json(1)" -> "applications/local-1426533911241/1/jobs",
    "job list from multi-attempt app json(2)" -> "applications/local-1426533911241/2/jobs",
    "one job json" -> "applications/local-1422981780767/jobs/0",
    "succeeded job list json" -> "applications/local-1422981780767/jobs?status=succeeded",
    "succeeded&failed job list json" ->
      "applications/local-1422981780767/jobs?status=succeeded&status=failed",
    "executor list json" -> "applications/local-1422981780767/executors",
    "stage list json" -> "applications/local-1422981780767/stages",
    "complete stage list json" -> "applications/local-1422981780767/stages?status=complete",
    "failed stage list json" -> "applications/local-1422981780767/stages?status=failed",
    "one stage json" -> "applications/local-1422981780767/stages/1",
    "one stage attempt json" -> "applications/local-1422981780767/stages/1/0",

    "stage task summary w shuffle write"
      -> "applications/local-1430917381534/stages/0/0/taskSummary",
    "stage task summary w shuffle read"
      -> "applications/local-1430917381534/stages/1/0/taskSummary",
    "stage task summary w/ custom quantiles" ->
      "applications/local-1430917381534/stages/0/0/taskSummary?quantiles=0.01,0.5,0.99",

    "stage task list" -> "applications/local-1430917381534/stages/0/0/taskList",
    "stage task list w/ offset & length" ->
      "applications/local-1430917381534/stages/0/0/taskList?offset=10&length=50",
    "stage task list w/ sortBy" ->
      "applications/local-1430917381534/stages/0/0/taskList?sortBy=DECREASING_RUNTIME",
    "stage task list w/ sortBy short names: -runtime" ->
      "applications/local-1430917381534/stages/0/0/taskList?sortBy=-runtime",
    "stage task list w/ sortBy short names: runtime" ->
      "applications/local-1430917381534/stages/0/0/taskList?sortBy=runtime",

    "stage list with accumulable json" -> "applications/local-1426533911241/1/stages",
    "stage with accumulable json" -> "applications/local-1426533911241/1/stages/0/0",
    "stage task list from multi-attempt app json(1)" ->
      "applications/local-1426533911241/1/stages/0/0/taskList",
    "stage task list from multi-attempt app json(2)" ->
      "applications/local-1426533911241/2/stages/0/0/taskList",

    "rdd list storage json" -> "applications/local-1422981780767/storage/rdd",
    "one rdd storage json" -> "applications/local-1422981780767/storage/rdd/0"
  )

  // run a bunch of characterization tests -- just verify the behavior is the same as what is saved
  //运行一堆特性测试--只要验证的行为是相同的,因为什么是保存在测试资源文件夹
  // in the test resource folder
  cases.foreach { case (name, path) =>
    test(name) {
      val (code, jsonOpt, errOpt) = getContentAndCode(path)
      code should be (HttpServletResponse.SC_OK)
      jsonOpt should be ('defined)
      errOpt should be (None)
      val json = jsonOpt.get
      //Apache Commons IO操作  IOUtils.toString 将文件读取为一个字符串
      // FileInputStream 从文件系统中的某个文件中获得输入字节
      val exp = IOUtils.toString(new FileInputStream(
        new File(expRoot, HistoryServerSuite.sanitizePath(name) + "_expectation.json")))
      // compare the ASTs so formatting differences don't cause failures
      //比较成本差异不会导致失败等格式
      import org.json4s._
      import org.json4s.jackson.JsonMethods._
      //比较json
      val jsonAst = parse(json)
      val expAst = parse(exp)
      assertValidDataInJson(jsonAst, expAst)
    }
  }

  test("download all logs for app with multiple attempts") {//下载所有的日志为应用程序与多个尝试
    doDownloadTest("local-1430917381535", None)
  }

  test("download one log for app with multiple attempts") {//下载一个日志的应用程序与多个尝试
    (1 to 2).foreach { attemptId => doDownloadTest("local-1430917381535", Some(attemptId)) }
  }

  test("download legacy logs - all attempts") {//下载旧的日志-所有的尝试
    doDownloadTest("local-1426533911241", None, legacy = true)
  }

  test("download legacy logs - single  attempts") {//下载旧的日志-单一的尝试
    (1 to 2). foreach {
      attemptId => doDownloadTest("local-1426533911241", Some(attemptId), legacy = true)
    }
  }

  // Test that the files are downloaded correctly, and validate them.
  //测试文件是否正确下载,并验证它们的正确性。
  def doDownloadTest(appId: String, attemptId: Option[Int], legacy: Boolean = false): Unit = {
    //尝试ID
    val url = attemptId match {
      case Some(id) =>
        new URL(s"${generateURL(s"applications/$appId")}/$id/logs")
      case None =>
        new URL(s"${generateURL(s"applications/$appId")}/logs")
    }

    val (code, inputStream, error) = HistoryServerSuite.connectAndGetInputStream(url)
    code should be (HttpServletResponse.SC_OK)// HTTP 状态码 200：OK
    inputStream should not be None //输入不等于空
    error should be (None)
    //是字节输入流的所有类的超类
    val zipStream = new ZipInputStream(inputStream.get)
    var entry = zipStream.getNextEntry
    entry should not be null
    val totalFiles = {
      if (legacy) {
        attemptId.map { x => 3 }.getOrElse(6)
      } else {
        attemptId.map { x => 1 }.getOrElse(2)
      }
    }
    var filesCompared = 0
    while (entry != null) {
      if (!entry.isDirectory) {
        val expectedFile = {
          if (legacy) {
            val splits = entry.getName.split("/")
            new File(new File(logDir, splits(0)), splits(1))
          } else {
            new File(logDir, entry.getName)
          }
        }
        val expected = Files.toString(expectedFile, Charsets.UTF_8)
        val actual = new String(ByteStreams.toByteArray(zipStream), Charsets.UTF_8)
        actual should be (expected)
        filesCompared += 1
      }
      entry = zipStream.getNextEntry
    }
    filesCompared should be (totalFiles)
  }

  test("response codes on bad paths") {//坏路径上的响应代码
    val badAppId = getContentAndCode("applications/foobar")//坏的路径
    badAppId._1 should be (HttpServletResponse.SC_NOT_FOUND)//HTTP 状态码 404:Not Found
    badAppId._3 should be (Some("unknown app: foobar"))//抛出异常

    val badStageId = getContentAndCode("applications/local-1422981780767/stages/12345")
    badStageId._1 should be (HttpServletResponse.SC_NOT_FOUND)//HTTP 状态码 404:Not Found
    badStageId._3 should be (Some("unknown stage: 12345"))//抛出异常

    val badStageAttemptId = getContentAndCode("applications/local-1422981780767/stages/1/1")
    badStageAttemptId._1 should be (HttpServletResponse.SC_NOT_FOUND)//HTTP 状态码 404:Not Found
    badStageAttemptId._3 should be (Some("unknown attempt for stage 1.  Found attempts: [0]"))//抛出异常

    val badStageId2 = getContentAndCode("applications/local-1422981780767/stages/flimflam")
    badStageId2._1 should be (HttpServletResponse.SC_NOT_FOUND)//HTTP 状态码 404:Not Found
    // will take some mucking w/ jersey to get a better error msg in this case
    //在这种情况下,会采取一些捣蛋汗来获得更好的错误

    val badQuantiles = getContentAndCode(
      "applications/local-1430917381534/stages/0/0/taskSummary?quantiles=foo,0.1")
    badQuantiles._1 should be (HttpServletResponse.SC_BAD_REQUEST)//HTTP 状态码 400：Bad Request
    badQuantiles._3 should be (Some("Bad value for parameter \"quantiles\".  Expected a double, " +
      "got \"foo\""))//抛出异常

    getContentAndCode("foobar")._1 should be (HttpServletResponse.SC_NOT_FOUND)
  }

  test("generate history page with relative links") {//生成具有相对链接的历史页
    val historyServer = mock[HistoryServer]
    val request = mock[HttpServletRequest]
    val ui = mock[SparkUI]
    val link = "/history/app1"
    val info = new ApplicationHistoryInfo("app1", "app1",
      List(ApplicationAttemptInfo(None, 0, 2, 1, "xxx", true)))
    when(historyServer.getApplicationList()).thenReturn(Seq(info))
    when(ui.basePath).thenReturn(link)
    when(historyServer.getProviderConfig()).thenReturn(Map[String, String]())
    val page = new HistoryPage(historyServer)

    // when
    val response = page.render(request)

    // then
    val links = response \\ "a"
    val justHrefs = for {
      l <- links
      attrs <- l.attribute("href")
    } yield (attrs.toString)
    justHrefs should contain(link)
  }

  def getContentAndCode(path: String, port: Int = port): (Int, Option[String], Option[String]) = {
    HistoryServerSuite.getContentAndCode(new URL(s"http://localhost:$port/api/v1/$path"))
  }

  def getUrl(path: String): String = {
    HistoryServerSuite.getUrl(generateURL(path))
  }

  def generateURL(path: String): URL = {
    new URL(s"http://localhost:$port/api/v1/$path")
  }
  //产生期望
  def generateExpectation(name: String, path: String): Unit = {
    val json = getUrl(path)
    val file = new File(expRoot, HistoryServerSuite.sanitizePath(name) + "_expectation.json")
    //FileWriter类从OutputStreamReader类继承而来。该类按字符向流中写入数据
    val out = new FileWriter(file)
    out.write(json)
    out.close()
  }
}

object HistoryServerSuite {
  def main(args: Array[String]): Unit = {
    // generate the "expected" results for the characterization tests.  Just blindly assume the
    // current behavior is correct, and write out the returned json to the test/resource files
    //测试生成描述的“预期”结果,只是盲目地假定当前行为是正确的,并将返回的json写入测试/资源文件

    val suite = new HistoryServerSuite
    FileUtils.deleteDirectory(suite.expRoot)//递归删除日志文件
    suite.expRoot.mkdirs()//创建一个日志目录
    try {
      suite.init()
      suite.cases.foreach { case (name, path) =>
        println(name+"==="+path)
        suite.generateExpectation(name, path)
      }
    } finally {
      suite.stop()
    }
  }
  //获取内容和代码
  def getContentAndCode(url: URL): (Int, Option[String], Option[String]) = {
    val (code, in, errString) = connectAndGetInputStream(url)
   //Apache Commons IO操作  IOUtils.toString 将文件读取为一个字符串
    //将InputStream转换成字符串
    val inString = in.map(IOUtils.toString)
    (code, inString, errString)
  }
  //连接并获取输入流
  def connectAndGetInputStream(url: URL): (Int, Option[InputStream], Option[String]) = {
    val connection = url.openConnection().asInstanceOf[HttpURLConnection]//获得HttpURL连接
    connection.setRequestMethod("GET")//get方式请求
    connection.connect()//连接
    val code = connection.getResponseCode()//获取URL响应状态码
    val inStream = try {
      Option(connection.getInputStream())//获取正文的数据
    } catch {
      case io: IOException => None
    }
    val errString = try {
      val err = Option(connection.getErrorStream())//如果连接失败但服务器仍然发送了有用数据,则返回错误流
      //将InputStream转换成字符串
      err.map(IOUtils.toString)
    } catch {
      case io: IOException => None
    }
    (code, inStream, errString)
  }

  //消毒路径
  def sanitizePath(path: String): String = {
    // this doesn't need to be perfect, just good enough to avoid collisions
    //这不需要是完美的,只是足够好以避免碰撞
    //\W：匹配任何非单词字符,
    // 例如stage task summary w shuffle read == stage_task_summary_w_shuffle_read
    path.replaceAll("\\W", "_")
  }
  //根据URL获得内容
  def getUrl(path: URL): String = {
    val (code, resultOpt, error) = getContentAndCode(path)
    if (code == 200) {
      resultOpt.get
    } else {
      throw new RuntimeException(
        "got code: " + code + " when getting " + path + " w/ error: " + error)
    }
  }
}
