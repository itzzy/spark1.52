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

package org.apache.spark.ml.classification

import breeze.linalg.{Vector => BV}

import org.apache.spark.SparkFunSuite
import org.apache.spark.ml.param.ParamsSuite
import org.apache.spark.mllib.classification.NaiveBayes.{Multinomial, Bernoulli}
import org.apache.spark.mllib.linalg._
import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.mllib.util.TestingUtils._
import org.apache.spark.mllib.classification.NaiveBayesSuite._
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.Row
/**
 * 朴素贝叶斯套件
 * 特征不需要标准化
 */
class NaiveBayesSuite extends SparkFunSuite with MLlibTestSparkContext {

  def validatePrediction(predictionAndLabels: DataFrame): Unit = {
    val numOfErrorPredictions = predictionAndLabels.collect().count {
      case Row(prediction: Double, label: Double) =>
        prediction != label
    }
    // At least 80% of the predictions should be on.
    //至少有80%的预测
    assert(numOfErrorPredictions < predictionAndLabels.count() / 5)
  }
  //验证模型的转换
  def validateModelFit(
      piData: Vector,
      thetaData: Matrix,
      model: NaiveBayesModel): Unit = {
    assert(Vectors.dense(model.pi.toArray.map(math.exp)) ~==
      Vectors.dense(piData.toArray.map(math.exp)) absTol 0.05, "pi mismatch")
    assert(model.theta.map(math.exp) ~== thetaData.map(math.exp) absTol 0.05, "theta mismatch")
  }
  //预期的多项式概率
  def expectedMultinomialProbabilities(model: NaiveBayesModel, feature: Vector): Vector = {
    val logClassProbs: BV[Double] = model.pi.toBreeze + model.theta.multiply(feature).toBreeze
    val classProbs = logClassProbs.toArray.map(math.exp)
    val classProbsSum = classProbs.sum
    Vectors.dense(classProbs.map(_ / classProbsSum))
  }
  //预计伯努利的概率
  def expectedBernoulliProbabilities(model: NaiveBayesModel, feature: Vector): Vector = {
    val negThetaMatrix = model.theta.map(v => math.log(1.0 - math.exp(v)))
    val negFeature = Vectors.dense(feature.toArray.map(v => 1.0 - v))
    val piTheta: BV[Double] = model.pi.toBreeze + model.theta.multiply(feature).toBreeze
    val logClassProbs: BV[Double] = piTheta + negThetaMatrix.multiply(negFeature).toBreeze
    val classProbs = logClassProbs.toArray.map(math.exp)
    val classProbsSum = classProbs.sum
    Vectors.dense(classProbs.map(_ / classProbsSum))
  }
  //验证概率
  def validateProbabilities(
      featureAndProbabilities: DataFrame,
      model: NaiveBayesModel,
      //模型类型(区分大小写)
      modelType: String): Unit = {
    featureAndProbabilities.collect().foreach {
      case Row(features: Vector, probability: Vector) => {
        assert(probability.toArray.sum ~== 1.0 relTol 1.0e-10)
        val expected = modelType match {
          case Multinomial =>
            expectedMultinomialProbabilities(model, features)
          case Bernoulli =>
            expectedBernoulliProbabilities(model, features)
          case _ =>
            throw new UnknownError(s"Invalid modelType: $modelType.")
        }
        assert(probability ~== expected relTol 1.0e-10)
      }
    }
  }

  test("params") {//参数
    ParamsSuite.checkParams(new NaiveBayes)
    val model = new NaiveBayesModel("nb", pi = Vectors.dense(Array(0.2, 0.8)),
      theta = new DenseMatrix(2, 3, Array(0.1, 0.2, 0.3, 0.4, 0.6, 0.4)))
    ParamsSuite.checkParams(model)
  }

  test("naive bayes: default params") {//朴素贝叶斯分类:默认参数
    val nb = new NaiveBayes
    assert(nb.getLabelCol === "label")//标签列名
    assert(nb.getFeaturesCol === "features")//特征
    assert(nb.getPredictionCol === "prediction")//预测
    assert(nb.getSmoothing === 1.0)//光滑
    assert(nb.getModelType === "multinomial")//模型类型(区分大小写)
  }

  test("Naive Bayes Multinomial") {//朴素贝叶斯分类
    val nPoints = 1000
    val piArray = Array(0.5, 0.1, 0.4).map(math.log)
    val thetaArray = Array(
      Array(0.70, 0.10, 0.10, 0.10), // label 0
      Array(0.10, 0.70, 0.10, 0.10), // label 1
      Array(0.10, 0.10, 0.70, 0.10)  // label 2
    ).map(_.map(math.log))
    val pi = Vectors.dense(piArray)
    val theta = new DenseMatrix(3, 4, thetaArray.flatten, true)

    val testDataset = sqlContext.createDataFrame(generateNaiveBayesInput(
      piArray, thetaArray, nPoints, 42, "multinomial"))
      /**
        +-----+-----------------+
        |label|         features|
        +-----+-----------------+
        |  2.0|[2.0,0.0,5.0,3.0]|
        |  2.0|[1.0,1.0,7.0,1.0]|
        |  0.0|[7.0,0.0,0.0,3.0]|
        |  0.0|[6.0,2.0,1.0,1.0]|
        |  2.0|[1.0,1.0,8.0,0.0]|
        +-----+-----------------+*/
      testDataset.show(5)
    val nb = new NaiveBayes().setSmoothing(1.0).setModelType("multinomial")
    //fit()方法将DataFrame转化为一个Transformer的算法
    val model = nb.fit(testDataset)

    validateModelFit(pi, theta, model)
    assert(model.hasParent)

    val validationDataset = sqlContext.createDataFrame(generateNaiveBayesInput(
      piArray, thetaArray, nPoints, 17, "multinomial"))
	   //transform()方法将DataFrame转化为另外一个DataFrame的算法
    val predictionAndLabels = model.transform(validationDataset).select("prediction", "label")
    validatePrediction(predictionAndLabels)
	   //transform()方法将DataFrame转化为另外一个DataFrame的算法
    val featureAndProbabilities = model.transform(validationDataset)
      .select("features", "probability")
    validateProbabilities(featureAndProbabilities, model, "multinomial")
  }
  //朴素贝叶斯伯努利
  test("Naive Bayes Bernoulli") {//伯努利方程是数学中的一种方程
    val nPoints = 10000
    val piArray = Array(0.5, 0.3, 0.2).map(math.log)
    val thetaArray = Array(
      Array(0.50, 0.02, 0.02, 0.02, 0.02, 0.02, 0.02, 0.02, 0.02, 0.02, 0.02, 0.40), // label 0
      Array(0.02, 0.70, 0.10, 0.02, 0.02, 0.02, 0.02, 0.02, 0.02, 0.02, 0.02, 0.02), // label 1
      Array(0.02, 0.02, 0.60, 0.02, 0.02, 0.02, 0.02, 0.02, 0.02, 0.02, 0.02, 0.30)  // label 2
    ).map(_.map(math.log))
    val pi = Vectors.dense(piArray)
    val theta = new DenseMatrix(3, 12, thetaArray.flatten, true)

    val testDataset = sqlContext.createDataFrame(generateNaiveBayesInput(
      piArray, thetaArray, nPoints, 45, "bernoulli"))
    val nb = new NaiveBayes().setSmoothing(1.0).setModelType("bernoulli")
    //fit()方法将DataFrame转化为一个Transformer的算法
    val model = nb.fit(testDataset)

    validateModelFit(pi, theta, model)
    assert(model.hasParent)

    val validationDataset = sqlContext.createDataFrame(generateNaiveBayesInput(
      piArray, thetaArray, nPoints, 20, "bernoulli"))
	//transform()方法将DataFrame转化为另外一个DataFrame的算法
    val predictionAndLabels = model.transform(validationDataset).select("prediction", "label")
    validatePrediction(predictionAndLabels)
	//transform()方法将DataFrame转化为另外一个DataFrame的算法
    val featureAndProbabilities = model.transform(validationDataset)
      .select("features", "probability")
    validateProbabilities(featureAndProbabilities, model, "bernoulli")
  }
}
