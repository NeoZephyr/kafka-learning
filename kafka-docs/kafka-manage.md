## 主题
### 创建主题
```sh
kafka-topics.sh --bootstrap-server broker_host:port --create --topic topic_name  --partitions 1 --replication-factor 1
```
从 Kafka 2.2 版本开始，推荐用 --bootstrap-server 参数替换 --zookeeper 参数。原因主要有两个：

1. 使用 --zookeeper 会绕过 Kafka 的安全体系
2. 使用 --bootstrap-server 与集群进行交互，越来越成为使用 Kafka 的标准姿势

### 查询主题
```sh
kafka-topics.sh --bootstrap-server broker_host:port --list
```

### 主题详情
```sh
kafka-topics.sh --bootstrap-server broker_host:port --describe --topic topic_name
```

### 修改主题分区（目前 Kafka 不允许减少某个主题的分区数）
```sh
kafka-topics.sh --bootstrap-server broker_host:port --alter --topic topic_name --partitions <新分区数>
```

### 删除主题
```sh
kafka-topics.sh --bootstrap-server broker_host:port --delete  --topic topic_name
```

主题有可能删除失败。最常见的原因有两个：副本所在的 Broker 宕机了；待删除主题的部分分区依然在执行迁移过程。可以采取以下措施：
1. 手动删除 ZooKeeper 节点 /admin/delete_topics 下以待删除主题为名的 znode
2. 手动删除该主题在磁盘上的分区目录
3. 在 ZooKeeper 中执行 rmr /controller，触发 Controller 重选举，刷新 Controller 缓存（可能造成大面积的分区 Leader 重选举）

### 变更副本数
创建一个 json 文件，显式提供 50 个分区对应的副本数
```
{"version":1, "partitions":[
 {"topic":"__consumer_offsets","partition":0,"replicas":[0,1,2]}, 
  {"topic":"__consumer_offsets","partition":1,"replicas":[0,2,1]},
  {"topic":"__consumer_offsets","partition":2,"replicas":[1,0,2]},
  {"topic":"__consumer_offsets","partition":3,"replicas":[1,2,0]},
  ...
  {"topic":"__consumer_offsets","partition":49,"replicas":[0,1,2]}
]}`
```
```sh
kafka-reassign-partitions.sh --zookeeper zookeeper_host:port --reassignment-json-file reassign.json --execute
```

### __consumer_offsets 占用太多的磁盘
用 jstack 命令查看 kafka-log-cleaner-thread 前缀的线程状态。通常情况下，这都是因为该线程挂掉了，无法及时清理此内部主题。如果的确是这个原因导致的，只能重启相应的 Broker 了

### 查看主题消息总数
```sh
kafka-run-class.sh kafka.tools.GetOffsetShell --broker-list kafka-host:port --time -2 --topic test-topic

kafka-run-class.sh kafka.tools.GetOffsetShell --broker-list kafka-host:port --time -1 --topic test-topic
```


## 消费者组
### 查询消费者组
```sh
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list
```
在之前的版本中还可以通过 zookeeper 参数来连接指定的 ZooKeeper 地址，因为在旧版的 Kafka 中可以将消费组的信息存储在 ZooKeeper 节点中

### 消费者组详情
```sh
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group <groupId>
```

消费组一共有 Dead、Empty、PreparingRebalance、CompletingRebalance、Stable 这几种状态，正常情况下，一个具有消费者成员的消费组的状态为 Stable

```sh
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group <groupId> --state

kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group <groupId> --members --verbose
```

### 删除消费者组
删除一个指定的消费组，如果消费组中有消费者成员正在运行，则删除操作会失败
```sh
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --delete --group <groupId>
```

### 查询消费者组位移
```sh
# CURRENT-OFFSET 表示该消费者当前消费的最新位移
# LOG-END-OFFSET 表示对应分区最新生产消息的位移
kafka-consumer-groups.sh --bootstrap-server kafka-host:port --describe --group test-group
```

### 查看消费者组的状态信息
```sh
kafka-console-consumer.sh --bootstrap-server kafka_host:port --topic __consumer_offsets --formatter "kafka.coordinator.group.GroupMetadataManager\$GroupMetadataMessageFormatter" --from-beginning
```

### 查看消费者组提交的位移数据
```sh
kafka-console-consumer.sh --bootstrap-server kafka_host:port --topic __consumer_offsets --formatter "kafka.coordinator.group.GroupMetadataManager\$OffsetsMessageFormatter" --from-beginning
```


## 位移管理
重置消费组内消费位移的功能，前提是消费组内没有正在运行的消费者成员

### Earliest 策略
表示将位移调整到主题当前最早位移处。这个最早位移不一定就是 0，因为很久远的消息会被 Kafka 自动删除，所以当前最早位移很可能是一个大于 0 的值

```sh
kafka-consumer-groups.sh --bootstrap-server kafka-host:port --group test-group --reset-offsets --all-topics --to-earliest -–execute
```

```java
Properties consumerProperties = new Properties();

// 禁止自动提交位移
consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, groupID);
consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokerList);

String topic = "test";
try (final KafkaConsumer<String, String> consumer = 
  new KafkaConsumer<>(consumerProperties)) {
         consumer.subscribe(Collections.singleton(topic));
         consumer.poll(0);
         consumer.seekToBeginning(
  consumer.partitionsFor(topic).stream().map(partitionInfo ->          
    new TopicPartition(topic, partitionInfo.partition()))
    .collect(Collectors.toList()));
}
```

### Latest 策略
表示把位移重设成最新末端位移。如果总共向某个主题发送了 15 条消息，那么最新末端位移就是 15。如果想跳过所有历史消息，从最新的消息处开始消费的话，可以使用 Latest 策略

```sh
kafka-consumer-groups.sh --bootstrap-server kafka-host:port --group test-group --reset-offsets --all-topics --to-latest --execute
```
```sh
# 重置指定主题的指定分区的消费位移
kafka-consumer-groups.sh --bootstrap-server kafka-host:port --group test-group --topic test-topic:2 --reset-offsets --all-topics --to-latest --execute
```

```java
consumer.seekToEnd(
  consumer.partitionsFor(topic).stream().map(partitionInfo ->          
  new TopicPartition(topic, partitionInfo.partition()))
  .collect(Collectors.toList()));
```

### Current 策略
表示将位移调整成消费者当前提交的最新位移。适用场景：修改代码重启消费者之后，发现有问题，需要回滚代码，同时也要把位移重设到消费者重启时的位置，那么可以使用 Current 策略

```sh
kafka-consumer-groups.sh --bootstrap-server kafka-host:port --group test-group --reset-offsets --all-topics --to-current --execute
```

```java
consumer.partitionsFor(topic).stream().map(info -> 
  new TopicPartition(topic, info.partition()))
  .forEach(tp -> {
  long committedOffset = consumer.committed(tp).offset();
  consumer.seek(tp, committedOffset);
});
```

### Specified-Offset 策略
表示消费者把位移值调整到你指定的位移处。在实际使用过程中，可能会出现 corrupted 消息无法被消费的情形，此时消费者程序会抛出异常，无法继续工作。碰到这个问题，可以尝试使用 Specified-Offset 策略来规避

```sh
kafka-consumer-groups.sh --bootstrap-server kafka-host:port --group test-group --reset-offsets --all-topics --to-offset <offset> --execute
```

```java
long targetOffset = 1234L;
for (PartitionInfo info : consumer.partitionsFor(topic)) {
  TopicPartition tp = new TopicPartition(topic, info.partition());
  consumer.seek(tp, targetOffset);
}
```

### Shift-By-N 策略
指定位移的相对数值

```sh
kafka-consumer-groups.sh --bootstrap-server kafka-host:port --group test-group --reset-offsets --shift-by <offset_N> --execute
```

```java
for (PartitionInfo info : consumer.partitionsFor(topic)) {
    TopicPartition tp = new TopicPartition(topic, info.partition());
    long targetOffset = consumer.committed(tp).offset() + 123L; 
    consumer.seek(tp, targetOffset);
}
```

### DateTime 策略
指定一个时间，然后将位移重置到该时间之后的最早位移处。如果需要重新消费昨天的数据，那么可以使用该策略重设位移到昨天 0 点

```sh
kafka-consumer-groups.sh --bootstrap-server kafka-host:port --group test-group --reset-offsets --to-datetime 2019-06-20T20:00:00.000 --execute
```

```java
long ts = LocalDateTime.of(
  2019, 6, 20, 20, 0).toInstant(ZoneOffset.ofHours(8)).toEpochMilli();
Map<TopicPartition, Long> timeToSearch = 
         consumer.partitionsFor(topic).stream().map(info -> 
  new TopicPartition(topic, info.partition()))
  .collect(Collectors.toMap(Function.identity(), tp -> ts));

for (Map.Entry<TopicPartition, OffsetAndTimestamp> entry : 
  consumer.offsetsForTimes(timeToSearch).entrySet()) {
    consumer.seek(entry.getKey(), entry.getValue().offset());
}
```

### Duration 策略
给定相对的时间间隔，然后将位移调整到距离当前给定时间间隔的位移处，具体格式是 PnDTnHnMnS。如果想将位移调回到 15 分钟前，那么可以指定 PT0H15M0S

```sh
kafka-consumer-groups.sh --bootstrap-server kafka-host:port --group test-group --reset-offsets --by-duration PT0H30M0S --execute
```

```java
Map<TopicPartition, Long> timeToSearch = consumer.partitionsFor(topic).stream()
         .map(info -> new TopicPartition(topic, info.partition()))
         .collect(Collectors.toMap(Function.identity(), tp -> System.currentTimeMillis() - 30 * 1000  * 60));

for (Map.Entry<TopicPartition, OffsetAndTimestamp> entry : 
         consumer.offsetsForTimes(timeToSearch).entrySet()) {
         consumer.seek(entry.getKey(), entry.getValue().offset());
}
```

### 文件策略
kafka-consumer-groups.sh 脚本中还有两个参数 dry-run 和 export，dry-run 是只打印具体的调整方案而不执行，export 是将位移调整方案以 CSV 的格式输出到控制台，而 execute 才会执行真正的消费位移重置
```sh
# 将消费位移再次往前调整 20 并输出结果，但是不执行
# 将输出结果保存到 offsets.csv 文件中
kafka-consumer-groups.sh --bootstrap-server localhost:9092  --group test-group --topic customer-delete --reset-offsets --shift-by -20 --export --dry-run
```

```sh
# 通过 from-file 参数从 offsets.csv 文件中获取位移重置策略，并且执行
kafka-consumer-groups.sh --bootstrap-server localhost:9092  --group test-group --topic customer-delete --reset-offsets --from-file offsets.csv --execute
```


## 生产与消费
### 生产消息
```sh
kafka-console-producer.sh --broker-list kafka-host:port --topic test-topic --request-required-acks -1 --producer-property compression.type=lz4
```

### 消费消息
```sh
kafka-console-consumer.sh --bootstrap-server kafka-host:port --topic test-topic --group test-group --from-beginning --consumer-property enable.auto.commit=false 
```

### 测试生产者性能
```sh
# 发送 1 千万条消息，每条消息大小是 1KB
kafka-producer-perf-test.sh --topic test-topic --num-records 10000000 --throughput -1 --record-size 1024 --producer-props bootstrap.servers=kafka-host:port acks=-1 linger.ms=2000 compression.type=lz4
```

### 测试消费者性能
```sh
kafka-consumer-perf-test.sh --broker-list kafka-host:port --messages 10000000 --topic test-topic
```

### 删除消息
在执行具体的删除动作之前需要先配置一个 JSON 文件，用来指定所要删除消息的分区及对应的位置
```js
{
    "partitions": [
        {
            "topic": "customer-delete",
            "partition": 0,
            "offset": 10
        },
        {
            "topic": "customer-delete",
            "partition": 1,
            "offset": 11
        },
        {
            "topic": "customer-delete",
            "partition": 2,
            "offset": 12
        }
    ],
    "version": 1
}
```
分别删除主题 customer-delete 下分区 0 中偏移量为 10、分区 1 中偏移量为 11 和分区 2 中偏移量为 12 的消息
```sh
kafka-delete-records.sh --bootstrap-server localhost:9092 --offset-json-file delete.json
```
Kafka 并不会直接删除消息，它在收到 DeleteRecordsRequest 请求之后，将指定分区的 logStartOffset 置为相应的请求值（比如分区 0 的偏移量 10），最终的删除消息的动作还是交由日志删除任务来完成的

### 查看消息文件数据
```sh
# 显示的是消息批次（RecordBatch）或消息集合（MessageSet）的元数据信息
kafka-dump-log.sh --files ../data_dir/kafka_1/test-topic-1/00000000000000000000.log
```
```sh
# 查看每条具体的消息
kafka-dump-log.sh --files ../data_dir/kafka_1/test-topic-1/00000000000000000000.log --deep-iteration
```
```sh
# 查看消息里面的实际数据
kafka-dump-log.sh --files ../data_dir/kafka_1/test-topic-1/00000000000000000000.log --deep-iteration --print-data-log
```


## KafkaAdminClient
```xml
<dependency>
    <groupId>org.apache.kafka</groupId>
    <artifactId>kafka-clients</artifactId>
    <version>2.3.0</version>
</dependency>
```

### 工作原理
#### 前端线程
负责将用户要执行的操作转换成对应的请求，然后再将请求发送到后端 I/O 线程的队列中
1. 构建对应的请求对象
2. 指定响应的回调逻辑，即从 Broker 端接收到请求对象之后要执行的动作
3. 将其请求对象放入到新请求队列

#### 后端 IO 线程
IO 线程使用了新请求队列、待发送请求队列和处理中请求队列来承载不同时期的请求对象。使用 3 个队列的原因是：目前新请求队列的线程安全是由 Java 的 monitor 锁来保证的。为了确保前端主线程不会因为 monitor 锁被阻塞，后端 I/O 线程会定期地将新请求队列中的所有实例全部搬移到待发送请求队列中进行处理。后续的待发送请求队列和处理中请求队列只由后端 IO 线程处理，因此无需任何锁机制来保证线程安全

当 IO 线程在处理某个请求时，它会显式地将该请求保存在处理中请求队列。一旦处理完成，IO 线程会自动地调用回调逻辑完成最后的处理。把这些都做完之后，IO 线程会通知前端主线程说结果已经准备完毕，这样前端主线程能够及时获取到执行操作的结果

后端 IO 线程名字的前缀是 kafka-admin-client-thread。有时候 AdminClient 程序貌似在正常工作，但执行的操作没有返回结果，或者 hang 住了，这可能是因为 IO 线程出现问题导致的。可以使用 jstack 命令去查看一下 AdminClient 程序，确认下 IO 线程是否在正常工作

### 创建主题
```java
String brokerList =  "localhost:9092";
String topic = "customer-delete";

Properties props = new Properties();
props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokerList);
props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
AdminClient client = AdminClient.create(props);

// 主题名称、分区数和副本因子
NewTopic newTopic = new NewTopic(topic, 4, (short) 1);
CreateTopicsResult result = client.createTopics(
    Collections.singleton(newTopic));
try {
    result.all().get(10, TimeUnit.SECONDS);
} catch (InterruptedException | ExecutionException e) {
    e.printStackTrace();
}
client.close(); 
```

也可以在创建主题时指定需要覆盖的配置。比如覆盖 cleanup.policy 配置
```java
Map<String, String> configs = new HashMap<>();
configs.put("cleanup.policy", "compact");
newTopic.configs(configs);
```

### 查看主题配置
```java
public static void describeTopicConfig() throws ExecutionException,
        InterruptedException {
    String brokerList = "localhost:9092";
    String topic = "customer-delete";

    Properties props = new Properties();
    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokerList);
    props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
    AdminClient client = AdminClient.create(props);

    ConfigResource resource =
        new ConfigResource(ConfigResource.Type.TOPIC, topic);
    DescribeConfigsResult result =
        client.describeConfigs(Collections.singleton(resource));
    Config config = result.all().get().get(resource);
    System.out.println(config);
    client.close();
}
```

### 修改主题配置
```java
ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
ConfigEntry entry = new ConfigEntry("cleanup.policy", "compact");
Config config = new Config(Collections.singleton(entry));
Map<ConfigResource, Config> configs = new HashMap<>();
configs.put(resource, config);
AlterConfigsResult result = client.alterConfigs(configs);
result.all().get();
```

### 增加主题分区
```java
NewPartitions newPartitions = NewPartitions.increaseTo(5);
Map<String, NewPartitions> newPartitionsMap = new HashMap<>();
newPartitionsMap.put(topic, newPartitions);
CreatePartitionsResult result = client.createPartitions(newPartitionsMap);
result.all().get();
```

### 查询消费者组位移
```java
String groupID = "test-group";
try (AdminClient client = AdminClient.create(props)) {
    ListConsumerGroupOffsetsResult result = client.listConsumerGroupOffsets(groupID);
    Map<TopicPartition, OffsetAndMetadata> offsets = 
        result.partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
    System.out.println(offsets);
}
```

### 获取 Broker 磁盘占用
```java
try (AdminClient client = AdminClient.create(props)) {
    DescribeLogDirsResult ret = client.describeLogDirs(Collections.singletonList(targetBrokerId)); // 指定Broker id
    long size = 0L;
    for (Map<String, DescribeLogDirsResponse.LogDirInfo> logDirInfoMap : ret.all().get().values()) {
        size += logDirInfoMap.values().stream().map(logDirInfo -> logDirInfo.replicaInfos).flatMap(
                topicPartitionReplicaInfoMap ->
                    topicPartitionReplicaInfoMap.values().stream().map(replicaInfo -> replicaInfo.size))
                       .mapToLong(Long::longValue).sum();
     }
     System.out.println(size);
}
```

### 主题合法性验证
Kafka broker 端有一个参数：create.topic.policy.class.name，默认值为 null，它提供了一个入口用来验证主题创建的合法性。只需要自定义实现 org.apache.kafka.server.policy.CreateTopicPolicy 接口，然后在 broker 端的配置文件 config/server.properties 中配置参数 create.topic.policy.class.name 的值为自定义实现的类的完整名称，然后启动服务
```java
public class Policy implements CreateTopicPolicy {

    // 在 Kafka 服务启动的时候执行
    public void configure(Map<String, ?> configs) {
    }

    // 在关闭 Kafka 服务时执行
    public void close() throws Exception {
    }

    // 鉴定主题参数的合法性，其在创建主题时执行
    public void validate(RequestMetadata requestMetadata)
        throws PolicyViolationException {
        if (requestMetadata.numPartitions() != null || 
                requestMetadata.replicationFactor() != null) {
            if (requestMetadata.numPartitions() < 5) {
                throw new PolicyViolationException("Topic should have at " +
                        "least 5 partitions, received: "+ 
                        requestMetadata.numPartitions());
            }
            if (requestMetadata.replicationFactor() <= 1) {
                throw new PolicyViolationException("Topic should have at " +
                        "least 2 replication factor, recevied: "+ 
                        requestMetadata.replicationFactor());
            }
        }
    }
}
```


## kafka 监控
### 主机监控
监控 Kafka 集群 Broker 所在的节点机器的性能

### JVM 监控
1. Full GC 发生频率和时长。长时间的停顿会令 Broker 端抛出各种超时异常
2. 活跃对象大小。这个指标是设定堆大小的重要依据
3. 应用线程总数

监控 Broker GC 日志，即以 kafkaServer-gc.log 开头的文件。一旦发现 Broker 进程频繁 Full GC，可以开启 G1 的 -XX:+PrintAdaptiveSizePolicy 开关，让 JVM 告诉你到底是谁引发了 Full GC

### 集群监控
1. 查看 Broker 进程是否启动，端口是否建立
2. 查看 Broker 端关键日志：服务器日志 server.log，控制器日志 controller.log，主题分区状态变更日志 state-change.log
3. 查看 Broker 端关键线程的运行状态。Log Compaction 线程：以 kafka-log-cleaner-thread 开头，一旦它挂掉了，所有 Compaction 操作都会中断；副本拉取消息的线程：以 ReplicaFetcherThread 开头，如果挂掉，则对应的 Follower 副本不再从 Leader 副本拉取消息，因而 Follower 副本的 Lag 会越来越大
4. 查看 Broker 端的关键 JMX 指标。BytesIn/BytesOut：Broker 端每秒入站和出站字节数，确保这组值不要接近网络带宽；NetworkProcessorAvgIdlePercent：网络线程池线程平均的空闲比例，通常来说，应该确保这个 JMX 值长期大于 30%；RequestHandlerAvgIdlePercent：I/O 线程池线程平均的空闲比例，该值应长期小于 30%；UnderReplicatedPartitions：即未充分备份的分区数，即并非所有的 Follower 副本都和 Leader 副本保持同步，表明分区有可能会出现数据丢失；ISRShrink/ISRExpand：即 ISR 收缩和扩容的频次指标；ActiveControllerCount：即当前处于激活状态的控制器的数量。正常情况下，Controller 所在 Broker 上的这个 JMX 指标值应该是 1，其他 Broker 上的这个值是 0。如果发现存在多台 Broker 上该值都是 1 的情况，一定要赶快处理，处理方式主要是查看网络连通性。这种情况通常表明集群出现了脑裂
5. 监控 Kafka 客户端。首先注意的是客户端所在的机器与 Kafka Broker 机器之间的网络往返时延；对于生产者而言，以 kafka-producer-network-thread 开头的线程负责实际消息发送，需要时刻关注；对于消费者而言，以 kafka-coordinator-heartbeat-thread 开头心跳线程也是必须要监控的一个线程。从 Producer 角度，需要关注的 JMX 指标是 request-latency，即消息生产请求的延时。这个 JMX 最直接地表征了 Producer 程序的 TPS；从 Consumer 角度来说，records-lag 和 records-lead 是两个重要的 JMX 指标。它们直接反映了 Consumer 的消费进度。如果使用了 Consumer Group，还需要关注 join rate 和 sync rate，它们说明了 Rebalance 的频繁程度。如果它们的值很高，那么就需要思考下 Rebalance 频繁发生的原因


## 监控工具
### JMXTool
--attributes: 要查询的 jmx 属性名称
--date-format: 日期格式
--jmx-url: jmx 接口，默认格式为 service:jmx:rmi:///jndi/rmi:<JMX 端口>/jmxrmi
--object-name: JMX MBean 名称
--reporting-interval: 实时查询的时间间隔，默认每 2 秒一次

```sh
# 查询 Broker 端每秒入站的流量
kafka-run-class.sh kafka.tools.JmxTool --object-name kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec --jmx-url service:jmx:rmi:///jndi/rmi://:9997/jmxrmi --date-format "YYYY-MM-dd HH:mm:ss" --attributes OneMinuteRate --reporting-interval 1000
```
```sh
# 查看当前激活的 Controller 数量
kafka-run-class.sh kafka.tools.JmxTool --object-name kafka.controller:type=KafkaController,name=ActiveControllerCount --jmx-url service:jmx:rmi:///jndi/rmi://:9997/jmxrmi --date-format "YYYY-MM-dd HH:mm:ss" --reporting-interval 1000
```

### Kafka Manager

### JMXTrans + InfluxDB + Grafana