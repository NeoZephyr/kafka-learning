## spark 介绍
Spark 是一个用来实现快速且通用的集群计算的平台。基于内存计算，提高了在大数据环境下数据处理的实时性，同时保证了高容错性和高可伸缩性。Spark 适用于各种各样原先需要多种不同的分布式平台实现的场景，包括批处理、迭代计算、交互式查询、流处理等

### spark 组成
1. Spark Core 实现了 Spark 的基本功能，包含任务调度、内存管理、错误恢复，以及与存储系统交互等模块
2. Spark Streaming 属于 Spark Core API 的扩展，支持实时数据流的可扩展、高吞吐、容错的流处理
3. Spark SQL 是 Spark 的一个结构化数据处理模块，提供了 DataFrame/Dataset 的编程抽象，可以看作一个分布式查询引擎。从 Spark 2.0 开始又引入了 Structured Streaming，它是建立在 Spark SQL 之上的可扩展和高容错的流处理引擎
4. MLlib 是 Spark 提供的具有机器学习功能的程序库
5. GraphX 是用来操作图的程序库，可以进行并行的图计算

### spark 安装及简单应用
```sh
tar zxvf spark-2.3.1-bin-hadoop2.7.tgz 
mv spark-2.3.1-bin-hadoop2.7 spark
cd spark
```
```sh
$SPARK_HOME/sbin
./start-all.sh

jps -l
```
Spark 启动后多了 Master 和 Worker 进程，分别代表主节点和工作节点。还可以通过 Spark 提供的 Web 界面来查看 Spark 的运行情况，比如可以通过 http://localhost:8080 查看 Master 的运行情况

Spark 中带有交互式的 shell，可以用作即时数据分析，通过 --master 参数来指定需要连接的集群
```sh
spark-shell --master spark://localhost:7077
```

通过 SparkContext 的 textFile() 方法读取文件
```scala
val rdd = sc.textFile("/opt/spark/bin/spark-shell")
```
使用 split() 方法按照空格进行分词，之后又通过 flatMap() 方法对处理后的单词进行展平，展平之后使用 map(x=>(x,1)) 对每个单词计数 1
```scala
val wordmap = rdd.flatMap(_.split(" ")).map(x=>(x,1))
```
使用 take(10) 方法获取前面 10 个单词统计的结果
```scala
wordreduce.take(10)
```
首先使用 map(x=>(x._2,x._1) 对单词统计结果的键和值进行互换，然后通过 sortByKey(false) 方法对值进行降序排序，然后再次通过 map(x=>(x._2,x._1) 将键和值进行互换
```scala
val wordsort = wordreduce.map(x=>(x._2,x._1)).sortByKey(false).map(x=>(x._2,x._1))
wordsort.take(10)
```

## spark 编程模型
### RDD
在 Spark 中，通过对分布式数据集的操作来表达计算意图，这些计算会自动在集群上并行执行。这样的数据集被称为弹性分布式数据集（Resilient Distributed Dataset），简称 RDD

RDD 支持 2 种类型的操作：转换操作（Transformation Operation）和行动操作（Action Operation）。有些资料还会细分为创建操作、转换操作、控制操作和行动操作 4 种类型

转换操作会由一个 RDD 生成一个新的 RDD；行动操作会对 RDD 计算出一个结果，并把结果返回驱动器程序，或者把结果存储到外部存储系统中。转换操作和行动操作的区别在于 Spark 计算 RDD 的方式不同。Spark 只有第一次在一个行动操作中用到时才会真正计算 RDD

转换操作：map、filter、groupBy、join、union、reduce、sort、partitionBy 等。返回值还是 RDD，不会马上提交给 Spark 集群运行
行动操作：count、collect、take、save、show 等。返回值不是 RDD，会形成 DAG 图，提交给 Spark 集群运行并立即返回结果

通过转换操作，从已有的 RDD 中派生出新的 RDD，Spark 会使用谱系图来记录这些不同 RDD 之间的依赖关系。Spark 需要用这些信息来按需计算每个 RDD，也可以依赖谱系图在持久化的 RDD 丢失部分数据时恢复丢失的数据

行动操作会把最终求得的结果返回驱动器程序，或者写入外部存储系统。由于行动操作需要生产实际的输出，所以它们会强制执行那些求值必须用到的 RDD 的转换操作

Spark 中 RDD 计算是以分区为单位的，将 RDD 划分为很多个分区分布到集群的节点中，分区的多少涉及对这个 RDD 进行并行计算的粒度

### 依赖关系
依赖关系还可以分为窄依赖和宽依赖。窄依赖是指每个父 RDD 的分区都至多被一个子 RDD 的分区使用，而宽依赖是指多个子 RDD 的分区依赖一个父 RDD 的分区。RDD 中行动操作的执行会以宽依赖为分界来构建各个调度阶段，各个调度阶段内部的窄依赖前后链接构成流水线

对于执行失败的任务，只要它对应的调度阶段的父类信息仍然可用，那么该任务就会分散到其他节点重新执行。如果某些调度阶段不可用，则重新提交相应的任务，并以并行方式计算丢失的地方。在整个作业中，如果某个任务执行缓慢，则系统会在其他节点上执行该任务的副本，并取最先得到的结果作为最终的结果

```xml
<dependency>
    <groupId>org.apache.spark</groupId>
    <artifactId>spark-core_2.11</artifactId>
    <version>2.3.1</version>
</dependency>
```

```scala
import org.apache.spark.{SparkConf, SparkContext}

object WordCount {
  def main(args: Array[String]): Unit ={

    // 创建 SparkConf 对象来配置应用程序
    val conf = new SparkConf().setAppName("WordCount").setMaster("local")
    val sc = new SparkContext(conf)

    // 创建一个代表文件中各行文本的 RDD
    val rdd = sc.textFile("/opt/spark-2.3.1-bin-hadoop2.7/bin/spark-shell")

    // map 这一段是窄依赖，reduceByKey 这一段是宽依赖
    val wordcount = rdd.flatMap(_.split(" ")).map(x=>(x,1)).reduceByKey(_+_)
    val wordsort = wordcount.map(x=>(x._2,x._1))
      .sortByKey(false).map(x=>(x._2,x._1))

    // 将排序后的结果存储起来
    wordsort.saveAsTextFile("/tmp/spark")

    // 关闭应用
    sc.stop()
  }
}
```

在 $SPARK_HOME/bin 目录中有一个 spark-submit 脚本，用于将应用快速部署到 Spark 集群

将应用打包成 jar 包并上传到 Spark 集群，然后通过 spark-submit 进行部署即可

```sh
# --class 用来指定应用程序的主类
# --executor-memory 用来指定执行器节点的内容
spark-submit --class scala.spark.demo.WordCount wordcount.jar --executor-memory 1G --master spark://localhost:7077
```

```sh
ls /tmp/spark
cat /tmp/spark/part-00000
```


## spark 运行结构
在分布式环境下，Spark 集群采用的是主从架构。在一个 Spark 集群中，有一个节点负责中央协调，调度各个分布式工作节点，这个中央协调节点被称为驱动器节点，与之对应的工作节点被称为执行器节点。驱动器节点可以和大量的执行器节点进行通信，它们都作为独立的进程运行。驱动器节点和所有的执行器节点一起被称为 Spark 应用

在集群上运行 Spark 应用的过程如下：
1. 通过 spark-submit 脚本提交应用
2. spark-submit 脚本启动驱动器程序，调用用户定义的 main() 方法
3. 驱动器程序与集群管理器通信，申请资源以启动执行器节点
4. 集群管理器为驱动器程序启动执行器节点
5. 驱动器执行用户应用中的操作。根据程序中定义的对 RDD 的转换操作和行动操作，驱动器节点把工作以任务的形式发送到执行器执行
6. 任务在执行器程序中进行计算并保存结果
7. 如果驱动器程序的 main() 方法退出，或者调用了 SparkContext.stop()，那么驱动器程序会中止执行器进程，并且通过集群管理器释放资源


## spark streaming
Spark Streaming 是 Spark 提供的对实时数据进行流式计算的组件，它是 Spark 核心 API 的一个扩展，具有吞吐量高、容错能力强的实时流数据处理系统，支持包括 Kafka、Flume、Kinesis 和 TCP 套接字等数据源，获取数据以后可以使用 map()、reduce()、join()、window() 等高级函数进行复杂算法的处理，处理结果可以存储到文件系统、数据库，或者展示到实时数据大盘等

Spark Streaming 使用离散化流作为抽象表示，叫作 DStream。DStream 是随着时间推移而收到的数据的序列。在内部，每个时间区间收到的数据都作为 RDD 存在，而 DStream 是由这些 RDD 组成的序列。创建出来的 DStream 支持两种操作：一种是转换操作，会生成一个新的 DStream；另一种是输出操作，可以把数据写入外部系统

Spark Streaming 会把实时输入的数据流以时间片 Δt 为单位切分成块，每块数据代表一个 RDD。流数据的 DStream 可以看作一组 RDD 序列，通过调用 Spark 核心的作业处理这些批数据，最终得到处理后的一批批结果数据

```xml
<dependency>
    <groupId>org.apache.spark</groupId>
    <artifactId>spark-streaming_2.11</artifactId>
    <version>2.3.1</version>
</dependency>
```

```scala
import org.apache.spark.SparkConf
import org.apache.spark.streaming.{Seconds, StreamingContext}

object StreamingWordCount {
  def main(args:Array[String]): Unit ={
    val conf = new SparkConf().setMaster("local[2]").setAppName("WordCount")

    // 指定 1 秒处理一次新数据的批次间隔作为输入
    val ssc = new StreamingContext(conf, Seconds(1))

    // 创建基于本地 9999 端口上收到的文本数据的 DStream
    val lines = ssc.socketTextStream("localhost", 9999)

    // flatMap() 就是将行数据流中的 RDD 转换成单词数据流中的 RDD
    val words = lines.flatMap(_.split(" "))
    val pairs = words.map(word => (word, 1))
    val wordCounts = pairs.reduceByKey(_ + _)
    wordCounts.print()
    ssc.start()
    ssc.awaitTermination()
  }
}
```
Spark Streaming 把 Spark 作业不断交给下面的 SparkContext 去调度执行。执行会在另一个线程中进行，所以需要调用 awaitTermination() 方法来等待流计算完成，以防止应用退出


## kafka 与 spark streaming
### 接收器 Receiver
Receiver 方式通过 KafkaUtils.createStream() 方法来创建一个 DStream 对象，它不关注消费位移的处理，在 Spark 任务执行异常时会导致数据丢失，如果要保证数据的可靠性，则需要开启预写式日志，简称 WAL（Write Ahead Logs），只有收到的数据被持久化到 WAL 之后才会更新 Kafka 中的消费位移。收到的数据和 WAL 存储位置信息被可靠地存储，如果期间出现故障，那么这些信息被用来从错误中恢复，并继续处理数据

WAL 的方式可以保证从 Kafka 中接收的数据不被丢失。但是在某些异常情况下，一些数据被可靠地保存到了 WAL 中，但是还没有来得及更新消费位移，这样会造成 Kafka 中的数据被 Spark 拉取了不止一次

在 Receiver 方式中，Spark 的 RDD 分区和 Kafka 的分区并不是相关的，因此增加 Kafka 中主题的分区数并不能增加 Spark 处理的并行度，仅仅增加了接收器接收数据的并行度

### 直接从 Kafka 中读取数据
Direct 方式是从 Spark 1.3 开始引入的，它通过 KafkaUtils.createDirectStream() 方法创建一个 DStream 对象，该方式中 Kafka 的一个分区与 Spark RDD 对应，通过定期扫描所订阅的 Kafka 每个主题的每个分区的最新偏移量以确定当前批处理数据偏移范围

与 Receiver 方式相比，Direct 方式不需要维护一份 WAL 数据，由 Spark Streaming 程序自己控制位移的处理，通常通过检查点机制处理消费位移，这样可以保证 Kafka 中的数据只会被 Spark 拉取一次

使用 Direct 的方式并不意味着实现了精确一次的语义（Exactly Once Semantics），如果要达到精确一次的语义标准，则还需要配合幂等性操作或事务性操作


```xml
<dependency>
    <groupId>org.apache.spark</groupId>
    <artifactId>spark-streaming-kafka-0-10_2.11</artifactId>
    <version>2.3.1</version>
</dependency>
<dependency>
    <groupId>org.apache.kafka</groupId>
    <artifactId>kafka-clients</artifactId>
    <version>2.0.0</version>
</dependency>
```
```java
Random random = new Random();
while (true) {
    String msg = String.valueOf(random.nextInt(10));
    ProducerRecord<String, String> message =
            new ProducerRecord<>(topic, msg);
    producer.send(message).get();
    TimeUnit.SECONDS.sleep(1);
}
```

```scala
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.SparkConf
import org.apache.spark.streaming.kafka010.ConsumerStrategies._
import org.apache.spark.streaming.kafka010.KafkaUtils
import org.apache.spark.streaming.kafka010.LocationStrategies._
import org.apache.spark.streaming.{Seconds, StreamingContext}

object StreamingWithKafka {
  private val brokers = "localhost:9092"
  private val topic = "topic-spark"
  private val group = "group-spark"
  private val checkpointDir = "/opt/kafka/checkpoint"

  def main(args: Array[String]): Unit = {
    val sparkConf = new SparkConf().setMaster("local")
      .setAppName("StreamingWithKafka")
    val ssc = new StreamingContext(sparkConf, Seconds(2))
    ssc.checkpoint(checkpointDir)                           

    val kafkaParams = Map[String, Object](
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> brokers,
      ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG -> 
        classOf[StringDeserializer],
      ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG -> 
        classOf[StringDeserializer],
      ConsumerConfig.GROUP_ID_CONFIG -> group,
      ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "latest",
      ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> (false:java.lang.Boolean)
    )

    val stream = KafkaUtils.createDirectStream[String, String](
      ssc, PreferConsistent, 
      Subscribe[String, String](List(topic), kafkaParams))

    // 消费读取到的 ConsumerRecord，并执行简单的求和计算
    val value = stream.map(record => {
      val intVal = Integer.valueOf(record.value())
      println(intVal)
      intVal
    }).reduce(_+_)
    value.print()

    ssc.start
    ssc.awaitTermination
  }
}
```

KafkaUtils.createDirectStream() 方法的的第二个参数是 LocationStrategies 类型的，用来指定 Spark 执行器节点上 KafkaConsumer 的分区分配策略

LocationStrategies 类型提供了 3 种策略
1. PerferBrokers 策略：必须保证执行器节点和 Kafka Broker 拥有相同的 host，即两者在相同的机器上，这样可以根据分区副本的 leader 节点来进行分区分配
2. PerferConsistent 策略：该策略将订阅主题的分区均匀地分配给所有可用的执行器，在绝大多数情况下都使用这种策略
3. PerferFixed 策略：允许开发人员指定分区与 host 之间的映射关系

KafkaUtils.createDirectStream() 方法中的第三个参数是 ConsumerStrategies 类型的，用来指定 Spark 执行器节点的消费策略，也有 3 种策略
1. Subscribe：通过指定集合进行订阅
2. SubscribePattern：通过正则表达式进行订阅
3. Assign：通过指定分区的方式进行订阅

使用 SubscribePattern 策略
```scala
val stream = KafkaUtils.createDirectStream[String,String](
  ssc, PreferConsistent,
  SubscribePattern[String,String](Pattern.compile("topic-.*"),kafkaParams)
)
```

使用 Assign 策略
```scala
val partitions = List(new TopicPartition(topic,0),
  new TopicPartition(topic,1),
  new TopicPartition(topic,2),
  new TopicPartition(topic,3))
val stream = KafkaUtils.createDirectStream[String,String](
  ssc, PreferConsistent,
  Assign[String, String](partitions, kafkaParams))
```

Spark Streaming 也支持从指定的位置处处理数据，前面演示的 3 种消费策略都可以支持，只需添加对应的参数即可。以 Subscribe 策略为例
```scala
val partitions = List(new TopicPartition(topic,0),
  new TopicPartition(topic,1),
  new TopicPartition(topic,2),
  new TopicPartition(topic,3))
val fromOffsets = partitions.map(partition => {
  partition -> 5000L
}).toMap
val stream = KafkaUtils.createDirectStream[String, String](
  ssc, PreferConsistent,
  Subscribe[String, String](List(topic), kafkaParams, fromOffsets))
```
使用滑动窗口操作，计算窗口间隔为 20s、滑动间隔为 2s 的窗口内的数值之和
```scala
val value = stream.map(record=>{
  Integer.valueOf(record.value())
}).reduceByWindow(_+_, _-_,Seconds(20),Seconds(2))
```

在 Direct 方式下，Spark Streaming 会自己控制消费位移的处理，原本应该保存到 Kafka 中的消费位移就无法提供准确的信息了。但是在某些情况下，需要获取当前 Spark Streaming 正在处理的消费位移。可以通过下面的程序来获取消费位移：
```scala
stream.foreachRDD(rdd=>{
  val offsetRanges = rdd.asInstanceOf[HasOffsetRanges].offsetRanges
  rdd.foreachPartition{iter=>
    val o: OffsetRange = offsetRanges(TaskContext.get.partitionId)
    println(s"${o.topic} ${o.partition} ${o.fromOffset} ${o.untilOffset}")
  }
})
```
这段代码需要在使用 KafkaUtils.createDirectStream() 方法创建 DStream 之后第一个调用

如果应用更加适合于批处理作业，那么在 Spark 中也可以使用 KafkaUtils.createRDD() 方法创建一个指定处理范围的 RDD
```scala
val offsetRanges = Array(
  OffsetRange(topic,0,0,100),
  OffsetRange(topic,1,0,100),
  OffsetRange(topic,2,0,100),
  OffsetRange(topic,3,0,100)
)
val rdd = KafkaUtils.createRDD(ssc,
  JavaConversions.mapAsJavaMap(kafkaParams),
  offsetRanges, PreferConsistent)
rdd.foreachPartition(records=>{
  records.foreach(record=>{
    println(record.topic()+":"+record.partition()+":"+ record.value())
  })
})
```
示例中的 OffsetRange 类型表示给定主题和分区中特定消息序列的下限和上限。OffsetRange(topic,0,0,100) 这行代码中标识从 topic 主题的第 0 个分区的偏移量 0 到偏移量 100（不包括）的 100 条消息


## spark sql
Spark SQL 是一个用于处理结构化数据的 Spark 组件，能够利用 Spark 进行结构化数据的存储和操作，结构化数据既可以来自外部结构化数据源，也可以通过向已有 RDD 增加 Schema 的方式得到

相比于 Spark RDD API，Spark SQL 包含了对结构化数据和在其上运算的更多信息，Spark SQL 使用这些信息进行额外的优化，使得对结构化数据的操作更高效和方便。Spark SQL 提供了多种使用的方式，包括 SQL、DataFrame API 和 Dataset API

Spark SQL 用于支持 SQL 查询，Spark SQL API 的返回结果是 Dataset/DataFrame。DataFrame 是一个分布式集合，其中数据被组织为命名的列。它在概念上等价于关系数据库中的表，但底层做了更多的优化。Dataset 从 Spark 1.6 开始加入，它的初衷是为了提升 RDD（强类型限制，可以使用 Lambda 函数）优化 SQL 执行引擎。Dataset 是 JVM 中的一个对象，可以作用于其他操作（map、flatMap、filter 等）

```scala
import org.apache.spark.sql.SparkSession 

val spark = SparkSession.builder()
    .appName("Spark SQL basic example").getOrCreate()

// 将 RDD 隐式转换为 DataFrame
import spark.implicits._
```

```scala
// 创建 RDD
val rdd = spark.sparkContext.textFile("people.txt")

// 使用 case class 定义 Schema
case class Person(name: String, age: Long)
defined class Person

// 通过 RDD 创建 DataFrame，这是以反射机制推断的实现方式
val df = rdd.map(_.split(",")).map(p=>Person(p(0),p(1).trim.toInt)).toDF()

// 展示 DataFrame 中的内容
df.show
```

```scala
// 将 DataFrame 转换为 Dataset
val ds = df.as[Person]

// 将 Dataset 转换为 DataFrame
val new_df = ds.toDF()

// Dataset是强类型的，而 DataFrame不是
df.filter($"age">20).count()

// DataFrame 采用下面的方式会报错
df.filter(_.age>20).count()

ds.filter(_.age>20).count()
```

Spark SQL 允许程序执行 SQL 查询，返回 DataFrame 结果：
```scala
// 注册临时表
df.registerTempTable("people_table")

// 使用 sql 运行 SQL 表达式
val result = spark.sql("SELECT name, age FROM people_table WHERE age>20")

// 显示查询结果
result.show
```


## structured streaming
Structured Streaming 吸取了开发 Spark SQL 和 Spark Streaming 过程中的经验教训，而且接收了 Spark 社区的众多反馈，是重新开发的全新流处理引擎，致力于为批处理和流处理提供统一的高性能 API

一个流的输出有多种模式，既可以是基于整个输入执行查询后的完整结果（Complete 模式），也可以选择只输出与上次查询相比的差异（Update 模式），或者就是简单地追加最新的结果（Append 模式）

```xml
<dependency>
    <groupId>org.apache.spark</groupId>
    <artifactId>spark-sql_2.11</artifactId>
    <version>2.3.1</version>
</dependency>
```

```scala
import org.apache.spark.sql.SparkSession

object StructuredStreamingWordCount {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession
      .builder()
      .master("local[2]")
      .appName("StructuredStreamingWordCount")
      .getOrCreate()

    // 将 RDD 隐式地转换为 DataFrame
    import spark.implicits._

    // 从 Socket 连接中创建一个 DataFrame
    // lines 变量表示的是一个流文本数据的无边界表，此表包含一个列名为 value 的字符串
    val lines = spark.readStream
      .format("socket")
      .option("host","localhost")
      .option("port",9999)
      .load()

    // 使用 .as[String] 将 DataFrame 转换为 String 类型的 Dataset
    // 使用 flatMap() 函数将每一行切分成多个单词
    // words 变量中包含了所有的单词
    val words = lines.as[String].flatMap(_.split(" "))

    // 通过分组来进行计数
    val wordCounts = words.groupBy("value").count()

    // 设置相应的流查询，开始接收数据并计数
    val query = wordCounts.writeStream
      .outputMode("complete")
      .format("console")
      .start()

    // 等待查询活动的中止，防止查询还处于活动状态时无端退出
    query.awaitTermination()
  }
}
```

基于事件时间窗口的单词统计案例
```scala
import spark.implicits._

// streaming DataFrame of schema { timestamp: Timestamp, word: String }
val words

// Group the data by window and word and compute the count of each group
val windowedCounts = words.groupBy(
  window($"timestamp", "10 minutes", "5 minutes"),
  $"word"
).count()
```
窗口大小为 10 分钟，并且窗口每 5 分钟滑动一次。words 变量是一个DataFrame 类型

Kafka 与 Structured Streaming 的集成比较简单，只需要将数据源改成 Kafka 即可
```xml
<dependency>
    <groupId>org.apache.spark</groupId>
    <artifactId>spark-sql-kafka-0-10_2.11</artifactId>
    <version>2.3.1</version>
</dependency>
```

```scala
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.SparkSession

object StructuredStreamingWithKafka {
  val brokerList = "localhost:9092"
  val topic = "topic-spark"

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder.master("local[2]")
      .appName("StructuredStreamingWithKafka").getOrCreate()

    import spark.implicits._

    val df = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers",brokerList)
      .option("subscribe",topic)
      .load()

    // 挑选出想要的 value 这一列
    // 将 DataFrame 转换为 String 类型的 Dataset
    val ds = df.selectExpr("CAST(value AS STRING)").as[String]

    val words = ds.flatMap(_.split(" ")).groupBy("value").count()

    val query = words.writeStream
      .outputMode("complete")
      .trigger(Trigger.ProcessingTime("10 seconds"))
      .format("console")
      .start()

    query.awaitTermination()
  }
}
```
在 Kafka 中有三种订阅模式：集合订阅的方式、正则表达式订阅的方式和指定分区的订阅方式。这里的 subscribe 选项对应集合订阅的方式，其他两种订阅方式在这里分别对应 subscribePattern 和 assign

如果进行的是一个批处理查询而不是流查询，那么可以使用 startingOffsets 和 endingOffsets 这两个选项指定一个合适的偏移量范围来创建一个 DataFrame/Dataset
```scala
val df = spark
    .read
    .format("kafka")
    .option("kafka.bootstrap.servers", "host1:port1,host2:port2")
    .option("subscribe", "topic1,topic2")
    .option("startingOffsets", 
        """{"topic1":{"0":23,"1":-2},"topic2":{"0":-2}}""")
    .option("endingOffsets", 
        """{"topic1":{"0":50,"1":-1},"topic2":{"0":-1}}""")
    .load()

df.selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)")
    .as[(String, String)]
```

可以通过在 Kafka 原生的参数前面添加一个 kafka. 的前缀来作为要配置的与 Kafka 有关的选型。但这一规则并不适合所有的参数，对于如下的 Kafka 参数是无法在使用 Structured Streaming 时设置的：
1. group.id：每次查询时会自动创建
2. auto.offset.reset：相关的功能由 startingOffsets 选项设定
3. key.serializer/value.serializer：总是使用 ByteArraySerializer 或 StringSerializer 进行序列化。可以使用 DataFrame 操作显式地将 key/value 序列化为字符串或字节数组
4. key.deserializer/value.deserializer：总是使用 ByteArrayDeserializer 将 key/value 反序列化为字节数组。可以使用 DataFrame 操作显式地反序列化 key/value
5. enable.auto.commit：这里不会提交任何消费位移
6. interceptor.classes：这里总是将 key 和 value 读取为字节数组，使用 ConsumerInterceptor 可能会破坏查询，因此是不安全的

可以看出这里既不提交消费位移，也不能设置 group.id，如果要通过传统的方式来获取流查询的监控数据是行不通的。可以直接通过 StreamingQuery 的 status() 和 lastProgress() 方法来获取当前流查询的状态和指标

```scala
println(query.status)
```

StreamingQuery 中还有一个 recentProgress() 方法用来返回最后几个进度的 StreamingQueryProgress 对象的集合

Spark 支持通过 Dropwizard 进行指标上报，对 Structured Streaming 而言，可以显式地将参数 spark.sql.streaming.metricsEnabled 设置为 true 来开启这个功能
```scala
spark.conf.set("spark.sql.streaming.metricsEnabled", "true")
```
```scala
spark.sql("SET spark.sql.streaming.metricsEnabled=true")
```

Structure Streaming 还提供了异步的方式来监控所有的流查询
```scala
spark.streams.addListener(new StreamingQueryListener() {

    // 在流查询开始的时候调用
    override def onQueryStarted(queryStarted: QueryStartedEvent): Unit = {
        println("Query started: " + queryStarted.id)
    }

    // 在流查询结束的时候调用
    override def onQueryTerminated(
          queryTerminated: QueryTerminatedEvent): Unit = {
        println("Query terminated: " + queryTerminated.id)
    }

    // 流查询每处理一次进度就会调用一下这个回调方法
    override def onQueryProgress(queryProgress: QueryProgressEvent): Unit = {
        println("Query made progress: " + queryProgress.progress)
    }
})
```

可以通过 onQueryProgress() 方法来将流查询的指标信息传递出去，以便对此信息进行相应的处理和图形化展示
