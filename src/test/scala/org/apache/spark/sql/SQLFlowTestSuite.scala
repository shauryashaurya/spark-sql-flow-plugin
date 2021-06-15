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

package org.apache.spark.sql

import java.io.File
import java.net.URI
import java.nio.file.Path

import org.apache.spark.SparkConf
import org.apache.spark.sql.catalyst.plans.SQLHelper
import org.apache.spark.sql.catalyst.util.{fileToString, stringToFile}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.util.Utils

class SQLFlowTestSuite extends QueryTest with SharedSparkSession with SQLHelper
  with SQLQueryTestHelper {

  private val regenerateGoldenFiles: Boolean = System.getenv("SPARK_GENERATE_GOLDEN_FILES") == "1"

  private def getWorkspaceFilePath(first: String, more: String*) = {
    if (!(sys.props.contains("spark.test.home") || sys.env.contains("SPARK_HOME"))) {
      fail("spark.test.home or SPARK_HOME is not set.")
    }
    val sparkHome = sys.props.getOrElse("spark.test.home", sys.env("SPARK_HOME"))
    java.nio.file.Paths.get(sparkHome, first +: more: _*)
  }


  protected val baseResourcePath = {
    getWorkspaceFilePath("src", "test", "resources", "sql-flow-tests").toFile
  }

  protected val inputFilePath = new File(baseResourcePath, "inputs").getAbsolutePath
  protected val goldenFilePath = new File(baseResourcePath, "results").getAbsolutePath

  protected val validFileExtensions = ".sql"

  protected override def sparkConf: SparkConf = super.sparkConf
    // Fewer shuffle partitions to speed up testing.
    .set(SQLConf.SHUFFLE_PARTITIONS, 4)

  // Create all the test cases.
  listTestCases.foreach(createScalaTestCase)

  /** A single SQL query's output. */
  protected case class QueryOutput(sql: String, schema: String, output: String) {
    override def toString: String = {
      // We are explicitly not using multi-line string due to stripMargin removing "|" in output.
      s"-- !query\n" +
        sql + "\n" +
        s"-- !query schema\n" +
        schema + "\n" +
        s"-- !query output\n" +
        output
    }
  }

  protected case class TestCase(name: String, inputFile: String, resultFile: String)

  protected def createScalaTestCase(testCase: TestCase): Unit = {
    test(testCase.name) {
      runTest(testCase)
    }
  }

  /** Run a test case. */
  protected def runTest(testCase: TestCase): Unit = {
    def splitWithSemicolon(seq: Seq[String]) = {
      seq.mkString("\n").split("(?<=[^\\\\]);")
    }

    def splitCommentsAndCodes(input: String) = {
      input.split("\n").partition(_.trim.startsWith("--"))
    }

    // List of SQL queries to run
    val input = fileToString(new File(testCase.inputFile))
    val (comments, code) = splitCommentsAndCodes(input)
    val queries = splitWithSemicolon(code).toSeq.map(_.trim).filter(_ != "").toSeq
      // Fix misplacement when comment is at the end of the query.
      .map(_.split("\n").filterNot(_.startsWith("--")).mkString("\n")).map(_.trim).filter(_ != "")

    runQueries(queries, testCase)
  }

  protected def runQueries(queries: Seq[String], testCase: TestCase): Unit = {
    // Create a local SparkSession to have stronger isolation between different test cases.
    // This does not isolate catalog changes.
    val localSparkSession = spark.newSession()

    // Runs the given SQL quries and gets data linearge as a Graphviz-formatted text
    queries.foreach(localSparkSession.sql(_).collect)

    SparkSession.setActiveSession(localSparkSession)
    val gvText = SQLFlow.catalogToSQLFlow().getOrElse("")

    val fileHeader = s"// Automatically generated by ${getClass.getSimpleName}\n\n"
    if (regenerateGoldenFiles) {
      val goldenOutput = s"$fileHeader$gvText"
      val resultFile = new File(testCase.resultFile)
      val parent = resultFile.getParentFile
      if (!parent.exists()) {
        assert(parent.mkdirs(), "Could not create directory: " + parent)
      }
      stringToFile(resultFile, goldenOutput)
    }

    withClue(s"${testCase.name}${System.lineSeparator()}") {
      // Read back the golden file.
      val expectedOutput = fileToString(new File(testCase.resultFile)).replaceAll(fileHeader, "")
      assert(expectedOutput === gvText)
    }
  }

  protected lazy val listTestCases: Seq[TestCase] = {
    listFilesRecursively(new File(inputFilePath)).flatMap { file =>
      val resultFile = file.getAbsolutePath.replace(inputFilePath, goldenFilePath) + ".gv"
      val absPath = file.getAbsolutePath
      val testCaseName = absPath.stripPrefix(inputFilePath).stripPrefix(File.separator)
      TestCase(testCaseName, absPath, resultFile) :: Nil
    }
  }

  /** Returns all the files (not directories) in a directory, recursively. */
  protected def listFilesRecursively(path: File): Seq[File] = {
    val (dirs, files) = path.listFiles().partition(_.isDirectory)
    // Filter out test files with invalid extensions such as temp files created
    // by vi (.swp), Mac (.DS_Store) etc.
    val filteredFiles = files.filter(_.getName.endsWith(validFileExtensions))
    filteredFiles ++ dirs.flatMap(listFilesRecursively)
  }

  /** Load built-in test tables into the SparkSession. */
  private def createTestTables(session: SparkSession): Unit = {
    import session.implicits._

    // Before creating test tables, deletes orphan directories in warehouse dir
    Seq("testdata").foreach { dirName =>
      val f = new File(new URI(s"${conf.warehousePath}/$dirName"))
      if (f.exists()) {
        Utils.deleteRecursively(f)
      }
    }

    (1 to 10).map(i => (i, i.toString)).toDF("key", "value")
      .repartition(1)
      .write
      .format("parquet")
      .saveAsTable("testdata")
  }

  private def removeTestTables(session: SparkSession): Unit = {
    session.sql("DROP TABLE IF EXISTS testdata")
  }
  override def beforeAll(): Unit = {
    super.beforeAll()
    createTestTables(spark)
  }

  override def afterAll(): Unit = {
    try {
      removeTestTables(spark)
    } finally {
      super.afterAll()
    }
  }
}