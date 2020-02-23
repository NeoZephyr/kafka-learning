## 重置位移
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