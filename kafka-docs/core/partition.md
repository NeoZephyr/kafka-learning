## 分区
分区的划分不仅为 Kafka 提供了可伸缩性、水平扩展的功能，还通过多副本机制来为 Kafka 提供数据冗余以提高数据可靠性

Kafka 会在 log.dir 或 log.dirs 参数所配置的目录下创建相应的主题分区，默认情况下这个目录为 /tmp/kafka-logs/



### 分区数
向一个只有 1 个分区和 1 个副本的主题中发送 100 万条消息，并且每条消息大小为 1024B，生产者对应的 acks 参数为 1

```sh
kafka-producer-perf-test.sh --topic customer-delete --num-records 1000000 --record-size 1024 --throughput -1 --producer-props bootstrap.servers=localhost:9092 acks=1
```
throughput 参数用来进行限流控制，当设定的值小于 0 时不限流，当设定的值大于 0 时，如果发送的吞吐量大于该值时就会被阻塞一段时间

kafka-producer-perf-test.sh 脚本中有一个参数 print-metrics，指定了这个参数时会在测试完成之后打印指标信息

```sh
kafka-consumer-perf-test.sh --topic customer-delete --messages 1000000 --broker-list localhost:9092
```

分区是 Kafka 中最小的并行操作单元，对生产者而言，分区的数据写入是可以并行化的；对消费者而言，Kafka 只允许单个分区中的消息被一个消费者线程消费，一个消费组的消费并行度完全依赖于所消费的分区数

一味地增加分区数并不能使吞吐量一直得到提升，并且分区数也并不能一直增加，如果超过默认的配置值，还会引起 Kafka 进程的崩溃

增加一个分区，对应的增加了一个文件描述符。对于一个高并发、高性能的应用来说，可以适当调大文件描述符
```sh
ulimit -n 65535

ulimit -Hn
ulimit -Sn
```

也可以在 /etc/security/limits.conf 文件中设置，参考如下：
```
root soft nofile 65535
root hard nofile 65535
```

limits.conf 文件修改之后需要重启才能生效。limits.conf 文件与 ulimit 命令的区别在于前者是针对所有用户的，而且在任何 shell 中都是生效的，即与 shell 无关，而后者只是针对特定用户的当前 shell 的设定。在修改最大文件打开数时，最好使用 limits.conf 文件来修改。也可以通过在 /etc/profile 文件中添加 ulimit 的设置语句来使全局生效



## 主题与分区

当主题中的消息包含 key 时（即 key 不为 null），根据 key 计算分区的行为就会受到影响，从而影响消息的顺序

目前 Kafka 只支持增加分区数而不支持减少分区数，比如减少主题的分区数，就会报出 InvalidPartitionException 的异常

为什么不支持减少分区？
实现此功能需要考虑的因素很多，比如删除的分区中的消息该如何处理？如果随着分区一起消失则消息的可靠性得不到保障；如果需要保留则又需要考虑如何保留。直接存储到现有分区的尾部，消息的时间戳就不会递增，如此对于 Spark、Flink 这类需要消息时间戳（事件时间）的组件将会受到影响；如果分散插入现有的分区，那么在消息量很大的时候，内部的数据复制会占用很大的资源，而且在复制期间，此主题的可用性又如何得到保障？与此同时，顺序性问题、事务性问题，以及分区和副本的状态机切换问题都是不得不面对的。反观这个功能的收益点却是很低的，如果真的需要实现此类功能，则完全可以重新创建一个分区数较小的主题，然后将现有主题中的消息按照既定的逻辑复制过去即可。




### 分区重分配
当集群中的一个节点下线时，如果节点上的分区是单副本的，这些分区就不可用了，而且在节点恢复前，相应的数据也处于丢失状态；如果节点上的分区是多副本的，那么位于这个节点上的 leader 副本的角色会转交到集群的其他 follower 副本上。Kafka 不会将这些失效的分区副本自动地迁移到集群中剩余的可用 broker 节点上

当集群中新增 broker 节点时，只有新创建的主题分区才有可能被分配到这个节点上，而之前的主题分区并不会自动分配到新加入的节点中，这样新节点的负载和原先节点的负载之间严重不均衡

为了解决上述问题，Kafka 提供了 kafka-reassign-partitions.sh 脚本来执行分区重分配的工作，它可以在集群扩容、broker 节点失效的场景下对分区进行迁移

kafka-reassign-partitions.sh 脚本的使用分为3个步骤：首先创建需要一个包含主题清单的 JSON 文件，其次根据主题清单和 broker 节点清单生成一份重分配方案，最后根据这份方案执行具体的重分配动作。

```sh
kafka-topics.sh --zookeeper localhost:2181/kafka --create --topic customer-delete --replication-factor 2 --partitions 4
```

由于某种原因，需要下线 brokerId 为 1 的 broker。节点，在此之前，将其上的分区副本迁移出去
```
{
    "topics":[
        {
            "topic":"customer-delete"
        }
    ],
    "version":1
}
```

根据这个 JSON 文件和指定所要分配的 broker 节点列表来生成一份候选的重分配方案

```sh
kafka-reassign-partitions.sh --zookeeper localhost:2181/kafka --generate --topics-to-move-json-file reassign.json --broker-list 0,2
```

执行上述命令打印出了两个 JSON 格式的内容。第一个 Current partition replica assignment 所对应的 JSON 内容为当前的分区副本分配情况，在执行分区重分配的时候将这个内容保存起来，以备后续的回滚操作。第二个 Proposed partition reassignment configuration 所对应的 JSON 内容为重分配的候选方案

将第二个 JSON 内容保存在一个 JSON 文件中，假定这个文件的名称为 project.json

```sh
kafka-reassign-partitions.sh --zookeeper localhost:2181/kafka --execute --reassignment-json-file project.json
```

可以查看主题中的所有分区副本都只在 0 和 2 的 broker 节点上分布了

分区重分配的基本原理是先通过控制器为每个分区添加新副本（增加副本因子），新的副本将从分区的 leader 副本那里复制所有的数据。复制完成之后，控制器将旧副本从副本清单里移除（恢复为原先的副本因子数）。需要注意的是，在重分配的过程中要确保有足够的空间

验证查看分区重分配的进度
```sh
kafka-reassign-partitions.sh --zookeeper localhost:2181/kafka --verify --reassignment-json-file project.json
```

分区重分配对集群的性能有很大的影响，需要占用额外的资源，比如网络和磁盘。在实际操作中，我们将降低重分配的粒度，分成多个小批次来执行，以此来将负面的影响降到最低。还需要注意的是，如果要将某个 broker 下线，那么在执行分区重分配动作之前最好先关闭或重启 broker。这样这个 broker 就不再是任何分区的 leader 节点了，它的分区就可以被分配给集群中的其他 broker。这样可以减少 broker 间的流量复制，以此提升重分配的性能，以及减少对集群的影响



一个 partition 只分布于一个 broker 上
一个 partition 对应一个文件夹
一个 partition 包含多个 segment
一个 segment 对应一个文件
记录之后被追加到 segment 中，不会被单独删除或者修改
清除过期日志时，直接删除一个或者多个 segment

Partitioner 决定 Producer 发送的消息到哪个 broker
实现 Partitioner 接口

```java
public class HashPartitioner implements Partitioner {
    public HashPartitioner(VerifiableProperties verifiableProperties) {}

    public int partition(Object key, int numPartitions) {
        if ((key instanceof Integer)) {
            return Math.abs(Integer.parseInt(key.toString())) % numPartitions;
        }
        return Math.abs(key.hashCode() % numPartitions);
    }
}

public class RoundRobinPartitioner implements Partitioner {
    private static AtomicLong next = new AtomicLong();

    public RoundRobinPartitioner(VerifiableProperties verifiableProperties) {}

    public int partition(Object key, int numPartitions) {
        long nextIndex = next.incrementAndGet();
        return (int)nextIndex % numPartitions;
    }
}
```
```java
public class ProducerDemo {

  static private final String TOPIC = "topic1";
  static private final String ZOOKEEPER = "localhost:2181";
  static private final String BROKER_LIST = "localhost:9092";
//  static private final int PARTITIONS = TopicAdmin.partitionNum(ZOOKEEPER, TOPIC);
  static private final int PARTITIONS = 3;


  public static void main(String[] args) throws Exception {
    Producer<String, String> producer = initProducer();
    sendOne(producer, TOPIC);
  }

  private static Producer<String, String> initProducer() {
    Properties props = new Properties();
    props.put("metadata.broker.list", BROKER_LIST);
    // props.put("serializer.class", "kafka.serializer.StringEncoder");
    props.put("serializer.class", StringEncoder.class.getName());
    props.put("partitioner.class", HashPartitioner.class.getName());
    // props.put("partitioner.class", "kafka.producer.DefaultPartitioner");
//    props.put("compression.codec", "0");
    props.put("producer.type", "async");
    props.put("batch.num.messages", "3");
    props.put("queue.buffer.max.ms", "10000000");
    props.put("queue.buffering.max.messages", "1000000");
    props.put("queue.enqueue.timeout.ms", "20000000");

    ProducerConfig config = new ProducerConfig(props);
    Producer<String, String> producer = new Producer<String, String>(config);
    return producer;
  }

  public static void sendOne(Producer<String, String> producer, String topic) throws InterruptedException {
    KeyedMessage<String, String> message1 = new KeyedMessage<String, String>(topic, "31", "test 31");
    producer.send(message1);
    Thread.sleep(5000);
    KeyedMessage<String, String> message2 = new KeyedMessage<String, String>(topic, "31", "test 32");
    producer.send(message2);
    Thread.sleep(5000);
    KeyedMessage<String, String> message3 = new KeyedMessage<String, String>(topic, "31", "test 33");
    producer.send(message3);
    Thread.sleep(5000);
    KeyedMessage<String, String> message4 = new KeyedMessage<String, String>(topic, "31", "test 34");
    producer.send(message4);
    Thread.sleep(5000);
    KeyedMessage<String, String> message5 = new KeyedMessage<String, String>(topic, "31", "test 35");
    producer.send(message5);
    Thread.sleep(5000);
    producer.close();
  }
}
```
```java
public class DemoConsumer {

  /**
   * @param args
   */
  public static void main(String[] args) {
     args = new String[]{"localhost:2181", "topic1", "group1", "consumer1"};
    if (args == null || args.length != 4) {
      System.err.print(
          "Usage:\n\tjava -jar kafka_consumer.jar ${zookeeper_list} ${topic_name} ${group_name} ${consumer_id}");
      System.exit(1);
    }
    String zk = args[0];
    String topic = args[1];
    String groupid = args[2];
    String consumerid = args[3];
    Properties props = new Properties();
    props.put("zookeeper.connect", zk);
    props.put("group.id", groupid);
    props.put("autooffset.reset", "largest");
    props.put("autocommit.enable", "true");
    props.put("client.id", "test");
    props.put("auto.commit.interval.ms", "1000");

    ConsumerConfig consumerConfig = new ConsumerConfig(props);
    ConsumerConnector consumerConnector = Consumer.createJavaConsumerConnector(consumerConfig);

    Map<String, Integer> topicCountMap = new HashMap<String, Integer>();
    topicCountMap.put(topic, 1);
    Map<String, List<KafkaStream<byte[], byte[]>>> consumerMap =
        consumerConnector.createMessageStreams(topicCountMap);

    KafkaStream<byte[], byte[]> stream1 = consumerMap.get(topic).get(0);
    ConsumerIterator<byte[], byte[]> it1 = stream1.iterator();
    while (it1.hasNext()) {
      MessageAndMetadata<byte[], byte[]> messageAndMetadata = it1.next();
      String message =
          String.format("Consumer ID:%s, Topic:%s, GroupID:%s, PartitionID:%s, Offset:%s, Message Key:%s, Message Payload: %s",
              consumerid,
              messageAndMetadata.topic(), groupid, messageAndMetadata.partition(),
              messageAndMetadata.offset(), new String(messageAndMetadata.key()),new String(messageAndMetadata.message()));
      System.out.println(message);
    }
  }
}
```

同步发送消息，返回 RecordMetaData 对象，包含了主键和分区信息
```java
ProducerRecord<String, String> record = new ProducerRecord<>("topic", "key", "value");

try {
    producer.send(record).get();
} catch (Exception e) {
    e.printStackTrace();
}
```
异步发送消息
```java
private class ProducerCallback implements Callback {
    public void onCompletion(RecordMetadata RecordMetadata, Exception e) {
        if (e != null) {
            e.printStackTrace();
        }
    }
}

ProducerRecord<String, String> record = new ProducerRecord<>("topic", "key", "value");
producer.send(record, new ProducerCallback());
```

ProducerRecord 对象中的键有两个用途，可以作为消息的附加信息，也可以用来决定消息写入主题对应的分区。如果键值为 null，就使用了默认的分区器，那么 ProducerRecord 将会被随机地发送到主题内各个可用的分区上。分区器使用轮询的算法将消息均衡地分布到各个分区上；如果键不为空，并且使用了默认的分区器，则会对键进行散列，根据散列值把消息映射到对应分区。

自定义分区器
```java
public class MyPartitioner implements Partitioner {
    public void configure(Map<String, ?> configs) {}

    public int partition(String topic, Object key, byte[] keyBytes,
        Object value, byte[] valueBytes, Cluster cluster) {
        List<PartitionInfo> partions = cluster.partitionsForTopic(topic);

        int numPartitions = partions.size();

        if ((keyBytes == null) || (!(key instanceof String))) {
            throw new InvalidRecordException("...");
        }

        // 分配到最后一个分区
        if (((String) key).equals("pain"))
            return numPartitions;

        // 分配到其它分区
        return (Math.abs(Utils.murmur2(keyBytes)) % (numPartitions - 1))
    }

    public void close() {}
}
```