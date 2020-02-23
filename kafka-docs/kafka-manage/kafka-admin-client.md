## 工作原理
### 前端线程
负责将用户要执行的操作转换成对应的请求，然后再将请求发送到后端 I/O 线程的队列中
1. 构建对应的请求对象
2. 指定响应的回调逻辑，即从 Broker 端接收到请求对象之后要执行的动作
3. 将其请求对象放入到新请求队列

### 后端 IO 线程
IO 线程使用了新请求队列、待发送请求队列和处理中请求队列来承载不同时期的请求对象。使用 3 个队列的原因是：目前新请求队列的线程安全是由 Java 的 monitor 锁来保证的。为了确保前端主线程不会因为 monitor 锁被阻塞，后端 I/O 线程会定期地将新请求队列中的所有实例全部搬移到待发送请求队列中进行处理。后续的待发送请求队列和处理中请求队列只由后端 IO 线程处理，因此无需任何锁机制来保证线程安全

当 IO 线程在处理某个请求时，它会显式地将该请求保存在处理中请求队列。一旦处理完成，IO 线程会自动地调用回调逻辑完成最后的处理。把这些都做完之后，IO 线程会通知前端主线程说结果已经准备完毕，这样前端主线程能够及时获取到执行操作的结果

后端 IO 线程名字的前缀是 kafka-admin-client-thread。有时候 AdminClient 程序貌似在正常工作，但执行的操作没有返回结果，或者 hang 住了，这可能是因为 IO 线程出现问题导致的。可以使用 jstack 命令去查看一下 AdminClient 程序，确认下 IO 线程是否在正常工作


## 创建主题
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


## 查看主题配置
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


## 修改主题配置
```java
ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
ConfigEntry entry = new ConfigEntry("cleanup.policy", "compact");
Config config = new Config(Collections.singleton(entry));
Map<ConfigResource, Config> configs = new HashMap<>();
configs.put(resource, config);
AlterConfigsResult result = client.alterConfigs(configs);
result.all().get();
```


## 增加主题分区
```java
NewPartitions newPartitions = NewPartitions.increaseTo(5);
Map<String, NewPartitions> newPartitionsMap = new HashMap<>();
newPartitionsMap.put(topic, newPartitions);
CreatePartitionsResult result = client.createPartitions(newPartitionsMap);
result.all().get();
```


## 查询消费者组位移
```java
String groupID = "test-group";
try (AdminClient client = AdminClient.create(props)) {
    ListConsumerGroupOffsetsResult result = client.listConsumerGroupOffsets(groupID);
    Map<TopicPartition, OffsetAndMetadata> offsets = 
        result.partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
    System.out.println(offsets);
}
```


## 获取 Broker 磁盘占用
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


## 主题合法性验证
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