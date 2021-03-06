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

package com.cloudera.spark

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

import org.apache.spark.ml.feature.{CountVectorizer, RegexTokenizer, StopWordsRemover}
import org.apache.spark.mllib.clustering.{LDA, OnlineLDAOptimizer}
import org.apache.spark.{SparkContext, SparkConf}
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.ml.feature.{CountVectorizer, RegexTokenizer, StopWordsRemover}
import org.apache.spark.sql._
/**
  * Infer the cluster topics on a set of 20 newsgroup data.
  *
  * The input text is text files, corresponding to emails in the newsgroup.
  * Each text file corresponds to one document.
  *
  *
  */
object TopicModeeling {

	def main (args: Array[String]) {

    var inputDir = "data/topicmodeling/newsgroup_20/"
    var stopWordFile = "data/topicmodeling/stopwords.txt"

    if(args.length > 1) {
      inputDir = args(0)
      stopWordFile = args(1)
    }

    val sparkConf = new SparkConf().setAppName("TopicModeeling")
    com.cloudera.spark.mllib.SparkConfUtil.setConf(sparkConf)
    val sc = new SparkContext(sparkConf)

    val numTopics: Int = 10
    val maxIterations: Int = 100
    val vocabSize: Int = 10000

    val sqlContext = new org.apache.spark.sql.SQLContext(sc)

    import sqlContext.implicits._

    val rawTextRDD = sc.wholeTextFiles(inputDir).map(_._2)
    val docDF = rawTextRDD
                  .zipWithIndex.toDF("text", "docId")
    val tokens = new RegexTokenizer()
                  .setGaps(false)
                  .setPattern("\\w+")
                  .setMinTokenLength(4)
                  .setInputCol("text")
                  .setOutputCol("words")
                  .transform(docDF)

    val stopwords = sc.textFile(stopWordFile).collect
    val filteredTokens = new StopWordsRemover()
                          .setStopWords(stopwords)
                          .setCaseSensitive(false)
                          .setInputCol("words")
                          .setOutputCol("filtered")
                          .transform(tokens)

    val cvModel = new CountVectorizer()
                    .setInputCol("filtered")
                    .setOutputCol("features")
                    .setVocabSize(vocabSize)
                    .fit(filteredTokens)

    val countVectors = cvModel
                        .transform(filteredTokens)
                        .select("docId", "features")
                        .map {
                            case Row(docId: Long, countVector: Vector) => (docId, countVector)
                        }.cache()

    val mbf = {
      val corpusSize = countVectors.count()
      2.0 / maxIterations + 1.0 / corpusSize
    }

    val lda = new LDA()
                  .setOptimizer(new OnlineLDAOptimizer()
                  .setMiniBatchFraction(math.min(1.0, mbf)))
                  .setK(numTopics)
                  .setMaxIterations(30)
                  .setDocConcentration(-1)
                  .setTopicConcentration(-1)

    val startTime = System.nanoTime()
    val ldaModel = lda.run(countVectors)
    val elapsed = (System.nanoTime() - startTime) / 1e9

    println(s"Finished training LDA model.  Summary:")
    println(s"Training time (sec)\t$elapsed")
    println(s"==========")

    val topicIndices = ldaModel.describeTopics(maxTermsPerTopic = 10)
    val vocabArray = cvModel.vocabulary
    val topics = topicIndices.map {
                  case (terms, termWeights) =>
                  terms.map(vocabArray(_)).zip(termWeights)
    }

    topics.zipWithIndex.foreach {
          case (topic, i) => println(s"TOPIC $i")
          topic.foreach { case (term, weight) => println(s"$term\t$weight") }
          println(s"==========")
    }
  }
}
