## 创建主题
```sh
kafka-topics.sh --bootstrap-server broker_host:port --create --topic topic_name  --partitions 10 --replication-factor 3
```
从 Kafka 2.2 版本开始，推荐用 --bootstrap-server 参数替换 --zookeeper 参数。原因主要有两个：

1. 使用 --zookeeper 会绕过 Kafka 的安全体系
2. 使用 --bootstrap-server 与集群进行交互，越来越成为使用 Kafka 的标准姿势


## 查询主题
```sh
kafka-topics.sh --bootstrap-server broker_host:port --list
```


## 主题详情
```sh
kafka-topics.sh --bootstrap-server broker_host:port --describe --topic topic_name1,topic_name2
```
增加 topics-with-overrides 参数列出包含了与集群不一样配置的主题
```sh
kafka-topics.sh --bootstrap-server broker_host:port --describe --topics-with-overrides
```
增加 under-replicated-partitions 参数找出所有包含失效副本的分区。包含失效副本的分区可能正在进行同步操作，也有可能同步发生异常，此时分区的 ISR 集合小于 AR 集合。对于通过该参数查询到的分区要重点监控，因为这很可能意味着集群中的某个 broker 已经失效或同步效率降低等
```sh
kafka-topics.sh --bootstrap-server broker_host:port --describe --topic topic_name --under-replicated-partitions
```
增加 unavailable-partitions 参数可以查看主题中没有 leader 副本的分区，这些分区已经处于离线状态，对于外界的生产者和消费者来说处于不可用的状态
```sh
kafka-topics.sh --bootstrap-server broker_host:port --describe --topic topic_name --unavailable-partitions
```


## 修改主题分区（目前 Kafka 不允许减少某个主题的分区数）
```sh
kafka-topics.sh --bootstrap-server broker_host:port --alter --topic topic_name --partitions <新分区数>
```


## 参数设置
```sh
# 创建时进行设置
kafka-topics.sh --bootstrap-server broker_host:port --create --topic topic_name  --partitions 10 --replication-factor 3 --config retention.ms=15552000000 --config max.message.bytes=5242880

kafka-topics.sh --bootstrap-server broker_host:port --alter --topic topic_name --config max.message.bytes=10485760

# 删除配置，恢复默认值
kafka-topics.sh --bootstrap-server broker_host:port --alter --topic topic_name --delete-config max.message.bytes

# 推荐修改时设置
kafka-configs.sh --zookeeper broker_host:port --entity-type topics --entity-name transaction --alter --add-config max.message.bytes=10485760
```
可以通过 zooKeeper 客户端查看所设置的参数，对应的 zooKeeper 节点为 /config/topics/topic_name
```
get /config/topics/topic_config
```



## 删除主题
1. 必须将 delete.topic.enable 参数配置为 true 才能够删除主题，这个参数的默认值就是 true，如果配置为 false，那么删除主题的操作将会被忽略。建议设置为 true
2. 如果要删除的主题是内部主题或者说主题不存在，那么删除时就会报错

删除主题可以释放一些资源，比如磁盘、文件句柄等
```sh
kafka-topics.sh --bootstrap-server broker_host:port --delete  --topic topic_name
```

脚本删除主题的行为本质上只是在 zooKeeper 中的 /admin/delete_topics 路径下创建一个与待删除主题同名的节点，以此标记该主题为待删除的状态。与创建主题相同的是，真正删除主题的动作也是由 Kafka 的控制器负责完成的。我们可以直接通过 ZooKeeper 的客户端来删除主题
```sh
create /admin/delete_topics/topic_name ""
```

还可以通过手动的方式来删除主题。主题中的元数据存储在 zooKeeper 中的 /brokers/topics 和 /config/topics 路径下，主题中的消息数据存储在 log.dir 或 log.dirs 配置的路径下，只需要手动删除这些地方的内容即可
```sh
# 删除 ZooKeeper 中的节点
delete /config/topics/topic_name
```
```sh
# 删除 ZooKeeper 中的节点 /brokers/topics/topic_name 及其子节点
rmr /brokers/topics/topic_name
```
```sh
# 删除集群中所有与主题 topic_name 有关的文件
# 集群中的各个 broker 节点中执行

rm -rf /tmp/kafka-logs/topic_name*
```


## 变更副本数
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


## __consumer_offsets 占用太多的磁盘
用 jstack 命令查看 kafka-log-cleaner-thread 前缀的线程状态。通常情况下，这都是因为该线程挂掉了，无法及时清理此内部主题。如果的确是这个原因导致的，只能重启相应的 Broker 了


## 查看主题消息总数
```sh
kafka-run-class.sh kafka.tools.GetOffsetShell --broker-list kafka-host:port --time -2 --topic test-topic

kafka-run-class.sh kafka.tools.GetOffsetShell --broker-list kafka-host:port --time -1 --topic test-topic
```