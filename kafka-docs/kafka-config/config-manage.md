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
kafka-configs.sh --zookeeper localhost:2181/kafka --describe --entity-type topics
```

增加配置实际上是往节点内容中添加属性的键值对，修改配置是在节点内容中修改相应属性的属性值，删除配置是删除相应的属性键值对

变更配置时还会在 ZooKeeper 中的 /config/changes/ 节点下创建一个以 config_change_ 为前缀的持久顺序节点（PERSISTENT_SEQUENTIAL），节点命名形式可以归纳为 /config/changes/config_change_<seqNo>
```sh
get /config/changes/config_change_0000000010
```
```
{"version":2,"entity_path":"topics/customer-delete"}
```


## 配置管理
kafka-configs.sh 脚本使用 entity-type 参数来指定操作配置的类型，并且使用 entity-name 参数来指定操作配置的名称。比如查看主题 customer-delete 的配置可以按如下方式执行：

### 查看配置
```sh
kafka-configs.sh --zookeeper localhost:2181/kafka --describe --entity-type topics --entity-name customer-delete
```
--entity-type 指定了查看配置的实体类型，--entity-name 指定了查看配置的实体名称。entity-type 只可以配置 4 个值：topics、brokers、clients 和 users

### 修改配置
```sh
# 修改主题级别参数
kafka-configs.sh --zookeeper zookeeper_host:port --entity-type topics --entity-name <topic_name> --alter --add-config max.message.bytes=10485760
```

```sh
# 修改主题限速
kafka-configs.sh --zookeeper zookeeper_host:port --alter --add-config 'leader.replication.throttled.rate=104857600,follower.replication.throttled.rate=104857600' --entity-type brokers --entity-name 0
```
若该主题的副本分别在 0、1、2、3 多个 Broker 上，那么你还要依次为 Broker 1、2、3 执行这条命令

```sh
# 为主题设置要限速的副本
kafka-configs.sh --zookeeper zookeeper_host:port --alter --add-config 'leader.replication.throttled.replicas=*,follower.replication.throttled.replicas=*' --entity-type topics --entity-name test
```

### 增加配置
```sh
kafka-configs.sh --zookeeper localhost:2181/kafka --alter --entity-type topics --entity-name customer-delete --add-config cleanup.policy=compact,max.message.bytes=10000
```

### 删除配置
```sh
kafka-configs.sh --zookeeper localhost:2181/kafka --alter --entity-type topics --entity-name customer-delete --delete-config cleanup.policy,max.message.bytes
```


## 动态 Broker 参数
### 使用场景
1. 动态调整 Broker 端各种线程池大小，实时应对突发流量
2. 动态调整 Broker 端连接信息或安全配置信息
3. 动态更新 SSL Keystore 有效期
4. 动态调整 Broker 端 Compact 操作性能
5. 实时变更 JMX 指标收集器 (JMX Metrics Reporter)

### 更改动态 Broker 参数
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

### 常用动态 Broker 参数
log.retention.ms：修改日志留存时间
num.io.threads 和 num.network.threads
ssl.keystore.type、ssl.keystore.location、ssl.keystore.password 和 ssl.key.password
num.replica.fetchers：增加该参数值，确保有充足的线程可以执行 Follower 副本向 Leader 副本的拉取


