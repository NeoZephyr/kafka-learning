## 配置管理
kafka-configs.sh 脚本使用 entity-type 参数来指定操作配置的类型，并且使用 entity-name 参数来指定操作配置的名称。比如查看主题 customer-delete 的配置可以按如下方式执行：

```sh
kafka-configs.sh --zookeeper localhost:2181/kafka --describe --entity-type topics --entity-name customer-delete
```
--entity-type 指定了查看配置的实体类型，--entity-name 指定了查看配置的实体名称。entity-type 只可以配置 4 个值：topics、brokers、clients 和 users


修改主题级别参数
```sh
kafka-configs.sh --zookeeper zookeeper_host:port --entity-type topics --entity-name <topic_name> --alter --add-config max.message.bytes=10485760
```

修改主题限速
```sh
kafka-configs.sh --zookeeper zookeeper_host:port --alter --add-config 'leader.replication.throttled.rate=104857600,follower.replication.throttled.rate=104857600' --entity-type brokers --entity-name 0
```
若该主题的副本分别在 0、1、2、3 多个 Broker 上，那么你还要依次为 Broker 1、2、3 执行这条命令

```sh
kafka-configs.sh --zookeeper zookeeper_host:port --alter --add-config 'leader.replication.throttled.replicas=*,follower.replication.throttled.replicas=*' --entity-type topics --entity-name test
```
为主题设置要限速的副本


### 增加配置
```sh
kafka-configs.sh --zookeeper localhost:2181/kafka --alter --entity-type topics --entity-name customer-delete --add-config cleanup.policy=compact,max.message.bytes=10000
```

```sh
kafka-configs.sh --zookeeper localhost:2181/kafka --describe --entity-type topics --entity-name customer-delete
```

```sh
kafka-topics.sh --zookeeper localhost:2181/kafka --describe --topic customer-delete --topics-with-overrides
```

### 删除配置
```sh
kafka-configs.sh --zookeeper localhost:2181/kafka --alter --entity-type topics --entity-name customer-delete --delete-config cleanup.policy,max.message.bytes
```

```sh
kafka-configs.sh --zookeeper localhost:2181/kafka --describe --entity-type topics --entity-name customer-delete
```







动态 Broker 参数使用场景
1. 动态调整 Broker 端各种线程池大小，实时应对突发流量
2. 动态调整 Broker 端连接信息或安全配置信息
3. 动态更新 SSL Keystore 有效期
4. 动态调整 Broker 端 Compact 操作性能
5. 实时变更 JMX 指标收集器 (JMX Metrics Reporter)

Kafka 将动态 Broker 参数保存在 ZooKeeper 中
```
ls /config/brokers
get /config/brokers/0
```

参数优先级：per-broker 参数 > cluster-wide 参数 > static 参数 > Kafka 默认值
```sh
# 设置全局值
# 如果要设置 cluster-wide 范围的动态参数，需要显式指定 entity-default
kafka-configs.sh --bootstrap-server kafka-host:port --entity-type brokers --entity-default --alter --add-config unclean.leader.election.enable=true
```
```sh
kafka-configs.sh --bootstrap-server kafka-host:port --entity-type brokers --entity-default --describe
```

```sh
kafka-configs.sh --bootstrap-server kafka-host:port --entity-type brokers --entity-name 1 --alter --add-config unclean.leader.election.enable=false
```
```sh
kafka-configs.sh --bootstrap-server kafka-host:port --entity-type brokers --entity-name 1 --describe
```

删除 cluster-wide 范围参数或 per-broker 范围参数
```sh
# 删除 cluster-wide 范围参数
kafka-configs.sh --bootstrap-server kafka-host:port --entity-type brokers --entity-default --alter --delete-config unclean.leader.election.enable
```

```sh
# 删除 per-broker 范围参数
kafka-configs.sh --bootstrap-server kafka-host:port --entity-type brokers --entity-name 1 --alter --delete-config unclean.leader.election.enable
```

查看动态 Broker 参数
直接运行无参数的 kafka-configs 脚本

最常用的动态 Broker 参数
log.retention.ms：修改日志留存时间
num.io.threads 和 num.network.threads
ssl.keystore.type、ssl.keystore.location、ssl.keystore.password 和 ssl.key.password

num.replica.fetchers
增加该参数值，确保有充足的线程可以执行 Follower 副本向 Leader 副本的拉取




## 配置原理
使用 kafka-configs.sh 脚本来变更配置时，会在 ZooKeeper 中创建一个命名形式为 /config/<entity-type>/<entity-name> 的节点，并将变更的配置写入这个节点，比如对于主题 customer-delete 而言，对应的节点名称为 /config/topics/customer-delete，节点中的数据内容为：
```sh
get /config/topics/customer-delete
```
```
{"version":1,"config":{"cleanup.policy":"compact","max.message.bytes":"10000"}}
```

查看配置时，就是从 /config/<entity-type>/<entity-name> 节点中获取相应的数据内容。如果使用 kafka-configs.sh 脚本查看配置信息时没有指定 entity-name 参数的值，则会查看 entity-type 所对应的所有配置信息
```sh
kafka-configs.sh --zookeeper localhost:2181/ kafka --describe --entity-type topics
```

增加配置实际上是往节点内容中添加属性的键值对，修改配置是在节点内容中修改相应属性的属性值，删除配置是删除相应的属性键值对

变更配置时还会在 ZooKeeper 中的 /config/changes/ 节点下创建一个以 config_change_ 为前缀的持久顺序节点（PERSISTENT_SEQUENTIAL），节点命名形式可以归纳为 /config/changes/config_change_<seqNo>
```sh
get /config/changes/config_change_0000000010
```
```
{"version":2,"entity_path":"topics/customer-delete"}
```


## 主题端参数
与主题相关的所有配置参数在 broker 层面都有对应参数。如果没有修改过主题的任何配置参数，那么就会使用 broker 端的对应参数作为其默认值

主题端参数与 broker 端参数的对照关系

1. cleanup.policy：日志压缩策略，默认值为 delete，还可以配置为 compact。对应的 broker 端参数为 log.cleanup.policy
2. compression.type：消息的压缩类型，默认值为 producer，表示保留生产者中所使用的原始压缩类型。还可以配置为 uncompressed、snappy、lz4、gzip。对应的 broker 端参数为 compression.type
3. delete.retention.ms：表示删除的数据能够保留的时间。默认值为 86400000，即 1 天。对应的 broker 端参数为 log.cleaner.delete.retention.ms
4. file.delete.delay.ms：清理文件之前可以等待的时间，默认值为 60000，即 1 分钟。对应的 broker 端参数为 log.segment.delete.delay.ms
5. flush.messages：需要收集多少消息才会将它们强制刷新到磁盘，默认值为 Long.MAX_VALUE，即让操作系统来决定。建议不要修改此参数的默认值。对应的 broker 端参数为 log.flush.interval.messages
6. flush.ms：需要等待多久才会将消息强制刷新到磁盘，默认值为 Long.MAX_VALUE，即让操作系统来决定。建议不要修改此参数的默认值。对应的 broker 端参数为 log.flush.interval.ms
7. follower.replication.throttled.replicas：配置被限制速率的主题所对应的 follower 副本列表。对应的 broker 端参数为 follower.replication.throttled.replicas
8. index.interval.bytes：用来控制添加索引项的频率。每超过这个参数所设置的消息字节数时就可以添加一个新的索引项，默认值为 4096。对应的 broker 端参数为 log.index.interval.bytes
9. leader.replication.throttled.replicas：配置被限制速率的主题所对应的 leader 副本列表。对应的 broker 端参数为 leader.replication.throttled.replicas
10. max.message.bytes：消息的最大字节数，默认值为 1000012。对应的 broker 端参数为 message.max.bytes
11. message.format.version：消息格式的版本，默认值为 2.0-IV1。对应的 broker 端参数为 log.message.format.version
12. message.timestamp.difference.max.ms：消息中自带的时间戳与 broker 收到消息时的时间戳之间最大的差值，默认值为 Long.MAX_VALUE。此参数只有在 meesage. timestamp.type 参数设置为 CreateTime 时才有效。对应的 broker 端参数为 log.message.timestamp.difference.max.ms
13. message.timestamp.type：消息的时间戳类型，默认值为 CreateTime，还可以设置为 LogAppendTime。对应的 broker 端参数为 log.message.timestamp.type
14. min.cleanable.dirty.ratio，日志清理时的最小污浊率，默认值为 0.5。对应的 broker 端参数为 log.cleaner.min.cleanable.ratio
15. min.compaction.lag.ms，日志再被清理前的最小保留时间，默认值为 0。对应的 broker 端参数为 log.cleaner.min.compaction.lag.ms
16. min.insync.replicas：分区 ISR 集合中至少要有多少个副本，默认值为 1。对应的 broker 端参数为 min.insync.replicas
17. preallocate：在创建日志分段的时候是否要预分配空间，默认值为 false。对应的 broker 端参数为 log.preallocate
18. retention.bytes：分区中所能保留的消息总量，默认值为 -1，即没有限制。对应的 broker 端参数为 log.retention.bytes
19. retention.ms：使用 delete 的日志清理策略时消息能够保留多长时间，默认值为 604800000，即 7 天。如果设置为 -1，则表示没有限制。对应的 broker 端参数为 log.retention.ms
20. segment.bytes：日志分段的最大值，默认值为 1073741824，即 1GB。对应的 broker 端参数为 log.segment.bytes
21. segment.index.bytes：日志分段索引的最大值，默认值为 10485760，即 10MB。对应的 broker 端参数为 log.index.size.max.bytes
22. segment.jitter.ms：滚动日志分段时，在 segment.ms 的基础之上增加的随机数，默认为 0。对应的 broker 端参数为 log.roll.jitter.ms
23. segment.ms：最长多久滚动一次日志分段，默认值为 604800000，即 7 天。对应的 broker 端参数为 log.roll.ms
24. unclean.leader.election.enable：是否可以从非 ISR 集合中选举 leader 副本，默认值为 false，如果设置为 true，则可能造成数据丢失。对应的 broker 端参数为 unclean.leader.election.enable
