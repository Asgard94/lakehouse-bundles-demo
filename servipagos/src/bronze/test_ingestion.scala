// Databricks notebook source
import org.apache.log4j.{Level, Logger}

// COMMAND ----------

val database =  "billPayments" //dbutils.widgets.get("database")
val collection = "billPayments" //dbutils.widgets.get("collection")
val startFrom = "2023-12-19T05:30:00Z" //dbutils.widgets.get("startFrom")
val domain = "develop" //dbutils.widgets.get("domain")

val CATALOG_DOMAIN = s"ENV_CATALOG_NAME_${domain.toUpperCase}"
//val TABLE_BRZ = s"""${sys.env.get(CATALOG_DOMAIN).get}.bronze.${collection}_brz"""
val TABLE_BRZ = "develop.bronze.billPayments_brz_yape3"

val ABFS_DOMAIN = s"ENV_URL_${domain.toUpperCase}_BRZ"
//val PATH_ABFS = s"""${sys.env.get(ABFS_DOMAIN).get}/nb/yape/${database}/data/${collection}"""
val PATH_ABFS = "abfss://develop@adlseu2yadlbackd02.dfs.core.windows.net/tables/bronze/develop/nb/yape/billPayments/data/billPayments_yape3"

val CHECKPOINT_DOMAIN =s"ENV_URL_${domain.toUpperCase}_CHK_BRZ"
//val PATH_CHECKPOINT = s"""${sys.env.get(CHECKPOINT_DOMAIN).get}/nb/yape/${database}/data/${collection}"""
val PATH_CHECKPOINT = "abfss://develop@adlseu2yadlbackd02.dfs.core.windows.net/checkpoints/bronze/develop/nb/yape/billPayments/data/billPayments_yape3"

val STREAM_QUERY_NAME = s"stream_${domain}_yape_${database}_${collection}_brz"

// COMMAND ----------

spark.sql(s"""
  create table if not exists $TABLE_BRZ   (id STRING, p_auditDate DATE) 
  USING DELTA PARTITIONED BY (p_auditDate)
  LOCATION  '$PATH_ABFS'
  TBLPROPERTIES (
    'delta.logRetentionDuration' = 'interval 30 days',   
    'delta.deletedFileRetentionDuration'= "interval 30 days",
    'delta.autoOptimize.autoCompact' = 'true',
    'delta.autoOptimize.optimizeWrite' = 'true'
  )
""")

// COMMAND ----------

val secretScope = "yape-managed-dbr-scope"  
val cosmosMasterKey = "lF9qUUjHs634IpOtkaUmLUFMRLSRw2YF1wrya08o2gHLPNDRkjMaVep8aOh03wpMRUa6Iz6FeDpkFh1QaH2Oog==" //dbutils.secrets.get(scope=secretScope, key = "yape-cosmos-db-key")
val cosmosEndpoint = "https://codbeu2yaped10.documents.azure.com:443/" //dbutils.secrets.get(scope= secretScope, key = "yape-cosmos-db-endpoint")

// COMMAND ----------

val configCosmos: Map[String, String]  = Map(
   "spark.cosmos.accountEndpoint" -> cosmosEndpoint,
   "spark.cosmos.accountKey" -> cosmosMasterKey,
   "spark.cosmos.database" -> database,
   "spark.cosmos.container" -> collection
   )

// COMMAND ----------

spark.sqlContext.setConf("spark.sql.shuffle.partitions","4")

// COMMAND ----------

val nameThroughputControl = s"${database.capitalize}SparkGlobalThroughputControl"

val dfRes = spark.readStream.format("cosmos.oltp.changefeed")
      .options(configCosmos)
      .option("spark.cosmos.read.maxItemCount",120)
      .option("spark.cosmos.read.inferSchema.enabled", "true")    
      .option("spark.cosmos.changeFeed.startFrom", startFrom)
      .option("spark.cosmos.throughputControl.enabled" , "true")
      .option("spark.cosmos.throughputControl.name" , nameThroughputControl) 
      .option("spark.cosmos.throughputControl.targetThroughputThreshold" , "0.75")
      .option("spark.cosmos.throughputControl.globalControl.database" , database ) 
      .option("spark.cosmos.throughputControl.globalControl.container" , "ThroughputControl")
      .option("spark.cosmos.read.inferSchema.includeSystemProperties","true")
      .load()

// COMMAND ----------

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.types.{ StringType }
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.Trigger

// COMMAND ----------

import com.google.gson.{FieldNamingPolicy, Gson, GsonBuilder}

// COMMAND ----------

import org.apache.spark.sql.streaming.StreamingQueryListener
import org.apache.spark.sql.streaming.StreamingQueryListener._


val myListener = new StreamingQueryListener {
  
 def buildGson(): Gson = {
    new GsonBuilder()
      .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
      .create()
  }

  def onQueryStarted(event: QueryStartedEvent): Unit = {

    println("on query started")
     println(event)
  }

 
  def onQueryProgress(event: QueryProgressEvent): Unit = {
    println("on query progress")

        val gson = buildGson()

    val jsonString = gson.toJson(event)


    println(jsonString)
  }


  def onQueryTerminated(event: QueryTerminatedEvent): Unit = {
    println("on query terminated")
     println(event)
  }
}

spark.streams.addListener(myListener)

// COMMAND ----------

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.{Column, DataFrame}

// COMMAND ----------

def wrapDocument(df: DataFrame): DataFrame = {
  df.withColumn("document", to_json(struct(df.columns.map(m => col(m)): _*)))
}

def castColumnsAsString(df: DataFrame): DataFrame = {
  df.select(df.columns.map(c => {
      if(df.schema(c).dataType.toString.contains("ArrayType") || 
        df.schema(c).dataType.toString.contains("StructType")){
          to_json(col(c)).as(c)
      }        
      else{
        col(c).cast(StringType)
      }     
    }
  ) : _*)
}

// COMMAND ----------

def upsertForeachBatch(microBatchOutputDF: DataFrame, batchId: Long){
  if(!microBatchOutputDF.isEmpty){

    val new_df = microBatchOutputDF
                    .transform(castColumnsAsString)
                    //.transform(wrapDocument)
                    .withColumn("auditTime", current_timestamp())
                    .withColumn("p_auditDate", current_date())                            
    
    new_df.write.format("delta")
            .option("mergeSchema", "true")
            .mode("append")
            .saveAsTable(TABLE_BRZ)            
  }                                                            

}

// COMMAND ----------

  val stream = dfRes
    .writeStream
    .trigger(Trigger.ProcessingTime("10 seconds"))  
    .option("checkpointLocation", PATH_CHECKPOINT )
    .queryName(STREAM_QUERY_NAME)
    .foreachBatch(upsertForeachBatch _)
    .start()
