## 序列化
生产者使用的序列化器和消费者使用的反序列化器是需要一一对应的

```java
public class StringSerializer implements Serializer<String> {
    private String encoding = "UTF8";

    // 该方法在创建 KafkaProducer 实例的时候调用的，主要用来确定编码类型
    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        String propertyName = isKey ? "key.serializer.encoding" : "value.serializer.encoding";
        Object encodingValue = configs.get(propertyName);
        if (encodingValue == null)
            encodingValue = configs.get("serializer.encoding");
        if (encodingValue instanceof String)
            encoding = (String) encodingValue;
    }

    @Override
    public byte[] serialize(String topic, String data) {
        try {
            if (data == null)
                return null;
            else
                return data.getBytes(encoding);
        } catch (UnsupportedEncodingException e) {
            throw new SerializationException("Error when serializing string to byte[] due to unsupported encoding " + encoding);
        }
    }
}
```

自定义序列化器
```java
public class CompanySerializer implements Serializer<Company> {
    @Override
    public void configure(Map configs, boolean isKey) {}

    @Override
    public byte[] serialize(String topic, Company data) {
        if (data == null) {
            return null;
        }
        byte[] name, address;
        try {
            if (data.getName() != null) {
                name = data.getName().getBytes("UTF-8");
            } else {
                name = new byte[0];
            }
            if (data.getAddress() != null) {
                address = data.getAddress().getBytes("UTF-8");
            } else {
                address = new byte[0];
            }
            ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + name.length + address.length);
            buffer.putInt(name.length);
            buffer.put(name);
            buffer.putInt(address.length);
            buffer.put(address);
            return buffer.array();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return new byte[0];
    }

    @Override
    public void close() {}
}
```

## 分区
### 分区器
如果消息 ProducerRecord 中没有指定 partition 字段，就需要依赖分区器，根据 key 这个字段来计算 partition 的值。默认分区器是 DefaultPartitioner，它实现了 Partitioner 接口

在默认分区器 DefaultPartitioner 的实现中，如果 key 不为 null，那么默认的分区器会对 key 进行哈希，最终根据得到的哈希值来计算分区号，拥有相同 key 的消息会被写入同一个分区。如果 key 为 null，那么消息将会以轮询的方式发往主题内的各个可用分区

### 分区策略
#### 轮询策略
Kafka Java 生产者 API 默认提供的分区策略。轮询策略有非常优秀的负载均衡表现，它总是能保证消息最大限度地被平均分配到所有分区上，故默认情况下它是最合理的分区策略

#### 随机策略
```java
List<PartitionInfo> partitions = cluster.partitionsForTopic(topic);
return ThreadLocalRandom.current().nextInt(partitions.size());
```

#### 按消息键保序策略
Kafka 默认分区策略同时实现了两种策略：如果指定了 Key，那么默认实现按消息键保序策略；如果没有指定 Key，则使用轮询策略


## 拦截器
生产者拦截器需要实现 ProducerInterceptor 接口

KafkaProducer 在将消息序列化和计算分区之前会调用生产者拦截器的 onSend() 方法来对消息进行相应的定制化操作。一般来说最好不要修改消息 ProducerRecord 的 topic、key 和 partition 等信息。如果要修改，则需确保对其有准确的判断，否则会与预想的效果出现偏差。比如修改 key 不仅会影响分区的计算，同样会影响 broker 端日志压缩（Log Compaction）的功能

KafkaProducer 会在消息被应答之前或消息发送失败时调用生产者拦截器的 onAcknowledgement() 方法，优先于用户设定的 Callback 之前执行。这个方法运行在 Producer 的 I/O 线程中，所以这个方法中实现的代码逻辑越简单越好，否则会影响消息的发送速度

close() 方法主要用于在关闭拦截器时执行一些资源的清理工作。在这 3 个方法中抛出的异常都会被捕获并记录到日志中，但并不会再向上传递

KafkaProducer 中可以指定多个拦截器以形成拦截链。拦截链会按照配置的顺序来一一执行（配置的时候，各个拦截器之间使用逗号隔开）

如果拦截链中的某个拦截器的执行需要依赖于前一个拦截器的输出，那么当前一个拦截器由于异常而执行失败，那么这个拦截器也就跟着无法继续执行。在拦截链中，如果某个拦截器执行失败，那么下一个拦截器会接着从上一个执行成功的拦截器继续执行


## 压缩
在 Kafka 中，压缩可能发生在两个地方：生产者端和 Broker 端

生产者程序中配置 `compression.type` 参数即表示启用指定类型的压缩算法
```java
// 开启GZIP压缩
properties.put("compression.type", "gzip");
```
表明该 Producer 的压缩算法使用的是 GZIP。这样 Producer 启动后生产的每个消息集合都是经 GZIP 压缩过的，故而能很好地节省网络传输带宽以及 Kafka Broker 端的磁盘占用

其实大部分情况下 Broker 从 Producer 端接收到消息后仅仅是原封不动地保存而不会对其进行任何修改，有两种例外情况就可能让 Broker 重新压缩消息：
1. Broker 端指定了和 Producer 端不同的压缩算法
2. Broker 端发生了消息格式转换

Kafka 将启用的压缩算法封装进消息集合中，这样当 Consumer 读取到消息集合时解压缩还原成之前的消息

除了在 Consumer 端解压缩，每个压缩过的消息集合在 Broker 端写入时都要发生解压缩操作，目的就是为了对消息执行各种验证。这种解压缩对 Broker 端性能是有一定影响的，特别是对 CPU 的使用率而言


## 幂等性
```java
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
```

`enable.idempotence` 被设置成 true 后，Producer 自动升级成幂等性 Producer，Kafka 自动进行消息去重。底层具体的原理很简单：就是在 Broker 端多保存一些字段。当 Producer 发送了具有相同字段值的消息后，Broker 能够判断消息是否已经重复，并将重复消息丢弃掉

幂等性具有以下局限：
1. 只能保证单分区上的幂等性，即一个幂等性 Producer 能够保证某个主题的一个分区上不出现重复消息，它无法实现多个分区的幂等性
2. 只能实现单会话上的幂等性，不能实现跨会话的幂等性。这里的会话，可以理解为 Producer 进程的一次运行。当重启 Producer 进程之后，这种幂等性保证就丧失了


## 事务
事务型 Producer 能够保证将消息原子性地写入到多个分区中。这批消息要么全部写入成功，要么全部失败。另外，Producer 重启后，Kafka 依然保证发送消息的精确一次处理

设置事务型 Producer
1. 开启 `enable.idempotence = true`
2. 设置 Producer 端参数 transactional.id 最好为其设置一个有意义的名字

```java
producer.initTransactions();
try {
    producer.beginTransaction();
    producer.send(record1);
    producer.send(record2);
    producer.commitTransaction();
} catch (KafkaException e) {
    producer.abortTransaction();
}
```

这段代码能够保证 Record1 和 Record2 要么全部提交成功，要么全部写入失败。即使写入失败，Kafka 也会把它们写入到底层的日志中，也就是说 Consumer 还是会看到这些消息。因此在 Consumer 端，读取事务型 Producer 发送的消息也是需要一些变更的：设置 `isolation.level` 参数的值即可。这个参数有两个取值：
1. read_uncommitted：这是默认值，表明 Consumer 能够读取到 Kafka 写入的任何消息，不论事务型 Producer 提交事务还是终止事务，其写入的消息都可以读取
2. read_committed：表明 Consumer 只会读取事务型 Producer 成功提交事务写入的消息。当然它也能看到非事务型 Producer 写入的所有消息


## 异常
### 可重试的异常
常见的可重试异常有：`NetworkException`、`LeaderNotAvailableException`、`UnknownTopicOrPartitionException`、`NotEnoughReplicasException`、`NotCoordinatorException` 等

对于可重试的异常，如果配置了 retries 参数，那么只要在规定的重试次数内自行恢复了，就不会抛出异常。retries 参数的默认值为 0，配置方式参考如下：
```java
props.put(ProducerConfig.RETRIES_CONFIG, 3);
```
如果重试了 3 次之后还没有恢复，那么仍会抛出异常，交给外层处理这些异常

### 不可重试的异常
例如 `RecordTooLargeException` 异常，表示所发送的消息太大，`KafkaProducer` 对此不会进行任何重试，直接抛出异常


## 异步发送
异步发送的方式，一般是在 `send()` 方法里指定一个 `Callback` 的回调函数，Kafka 在返回响应时调用该函数来实现异步的发送确认

```java
producer.send(record1, callback1);
producer.send(record2, callback2);
```
对于同一个分区而言，如果消息 record1 于 record2 之前先发送，那么 KafkaProducer 就可以保证对应的 callback1 在 callback2 之前调用，也就是说，回调函数的调用也可以保证分区有序


## 关闭客户端
`close()` 方法会阻塞等待之前所有的发送请求完成后再关闭 KafkaProducer。与此同时，KafkaProducer 还提供了一个带超时时间的 `close()` 方法。如果调用了带超时时间 timeout 的 `close()` 方法，那么只会在等待 timeout 时间内来完成所有尚未完成的请求处理，然后强行退出。在实际应用中，一般使用的都是无参的 `close()` 方法




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