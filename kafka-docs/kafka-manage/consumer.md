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