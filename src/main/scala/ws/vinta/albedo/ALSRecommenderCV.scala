package ws.vinta.albedo

import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.recommendation.ALS
import org.apache.spark.ml.tuning.{CrossValidator, ParamGridBuilder}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.{collect_list, row_number}
import ws.vinta.albedo.evaluators.RankingEvaluator
import ws.vinta.albedo.preprocessors.RecommendationFormatter
import ws.vinta.albedo.utils.DataSourceUtils.loadRepoStarring
import ws.vinta.albedo.utils.Settings

object ALSRecommenderCV {
  def main(args: Array[String]): Unit = {
    implicit val spark: SparkSession = SparkSession
      .builder()
      .appName("ALSRecommenderCV")
      .getOrCreate()

    import spark.implicits._

    val sc = spark.sparkContext
    sc.setCheckpointDir(s"${Settings.dataDir}/checkpoint")

    // Load Data

    val rawRepoStarringDS = loadRepoStarring()
    rawRepoStarringDS.cache()
    rawRepoStarringDS.printSchema()

    // Build Pipeline

    val als = new ALS()
      .setImplicitPrefs(true)
      .setSeed(42)
      .setColdStartStrategy("drop")
      .setUserCol("user_id")
      .setItemCol("repo_id")
      .setRatingCol("starring")

    val recommendationFormatter = new RecommendationFormatter()
      .setUserCol("user_id")
      .setItemCol("repo_id")
      .setPredictionCol("prediction")
      .setOutputCol("recommendations")

    val pipeline = new Pipeline()
      .setStages(Array(als, recommendationFormatter))

    // Cross-validate Model

    val paramGrid = new ParamGridBuilder()
      .addGrid(als.rank, Array(50))
      .addGrid(als.regParam, Array(0.01))
      .addGrid(als.alpha, Array(0.01))
      .addGrid(als.maxIter, Array(25))
      .build()

    val k = 15

    val windowSpec = Window.partitionBy($"user_id").orderBy($"starred_at".desc)
    val userActualItemsDF = rawRepoStarringDS
      .withColumn("row_number", row_number().over(windowSpec))
      .where($"row_number" <= k)
      .groupBy($"user_id")
      .agg(collect_list($"repo_id").alias("items"))
    userActualItemsDF.cache()

    val rankingEvaluator = new RankingEvaluator(userActualItemsDF)
      .setMetricName("ndcg@k")
      .setK(k)

    val cv = new CrossValidator()
      .setEstimator(pipeline)
      .setEstimatorParamMaps(paramGrid)
      .setEvaluator(rankingEvaluator)
      .setNumFolds(2)

    val cvModel = cv.fit(rawRepoStarringDS)

    // Show Best Parameters

    cvModel.getEstimatorParamMaps
      .zip(cvModel.avgMetrics)
      .sortWith(_._2 > _._2) // (paramMaps, metric)
      .foreach((pair: (ParamMap, Double)) => {
        println(s"${pair._2}: ${pair._1}")
      })

    spark.stop()
  }
}