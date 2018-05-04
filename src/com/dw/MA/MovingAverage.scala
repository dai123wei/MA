package com.dw.MA

import org.apache.spark.{Partitioner, SparkConf, SparkContext}
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import scala.collection.mutable

object MovingAverage {

  def main(args: Array[String]): Unit = {

    val conf = new SparkConf().setAppName("MovingAverage").setMaster("local[*]")
    val sc = new SparkContext(conf)

    val brodcastWindow = sc.broadcast(3)

    sc.textFile("hdfs://localhost:9000/user/dw/input/MoveAverage.txt")
      .map(line => {
        val tokens = line.split(",")
        val timestamp = DateTime.parse(tokens(1), DateTimeFormat.forPattern("yyyy-MM-dd")).getMillis
        (CompositeKey(tokens(0), timestamp), TimeSeriesData(timestamp, tokens(2).toDouble))
      })
      // 按照股票代码和时间进行排序
      .repartitionAndSortWithinPartitions(new CompositeKeyPartitioner(5))
      .map(k => (k._1.stockSymbol, k._2))
      .groupByKey()
      .mapValues(itr => {
        val queue = new mutable.Queue[Double]()
        for (tsd <- itr) yield {
          queue.enqueue(tsd.closingStockPrice)
          if (queue.size > brodcastWindow.value)
            queue.dequeue()

          (new DateTime(tsd.timeStamp).toString("yyyy-MM-dd"), tsd.closingStockPrice, queue.sum / queue.size)
        }
      })
      .flatMap(kv => kv._2.map(v => (kv._1, v._1, v._2, v._3)))
      .saveAsTextFile("hdfs://localhost:9000/user/dw/out_MA")

    sc.stop()
  }

  class CompositeKeyPartitioner(partitions: Int) extends Partitioner {
    override def numPartitions: Int = partitions

    override def getPartition(key: Any): Int = key match {
      case CompositeKey(stockSymbol, _) => math.abs(stockSymbol.hashCode % numPartitions)
      case null => 0
      case _ => math.abs(key.hashCode() % numPartitions)
    }

    override def equals(other: Any): Boolean = other match {
      case h: CompositeKeyPartitioner => h.numPartitions == numPartitions
      case _ => false
    }

    override def hashCode: Int = numPartitions
  }

}

case class CompositeKey(stockSymbol: String, timeStamp: Long)

object CompositeKey {
  implicit def ord[A <: CompositeKey]: Ordering[A] = Ordering.by(fk => (fk.stockSymbol, fk.timeStamp))
}

case class TimeSeriesData(timeStamp: Long, closingStockPrice: Double)