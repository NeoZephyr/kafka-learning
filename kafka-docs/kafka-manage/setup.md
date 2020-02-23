##  环境搭建
### 环境变量
```sh
export JAVA_HOME=
export ZOOKEEPER_HOME=
export PATH=$PATH:$JAVA_HOME/bin
```
```sh
source /etc/profile
```

### zookeeper
zoo.cfg
```sh
# ZooKeeper 服务器心跳时间，单位为 ms
tickTime=2000
# 投票选举新 leader 的初始化时间
initLimit=10
# 响应超过 syncLimit * tickTime，leader 认为 follower 死掉，从服务器列表中删除 follower
syncLimit=5
# 数据目录
dataDir=/tmp/zookeeper/data
# 日志目录
dataLogDir=/tmp/zookeeper/log
# zooKeeper 对外服务端口
clientPort=2181
```
```sh
zkServer.sh start
zkServer.sh status
```

### kafka
server.properties
```sh
# broker 的编号，如果集群中有多个 broker，则每个 broker 的编号需要设置的不同
broker.id=0

# broker 对外提供的服务入口地址
# listeners=PLAINTEXT://:9092
listeners=PLAINTEXT://node01:9092,PLAINTEXT://node02:9092,PLAINTEXT://node03:9092

# 存放消息日志文件的地址
log.dirs=/tmp/kafka-logs

# zooKeeper 集群地址
# zookeeper.connect=node01:2181/kafka
zookeeper.connect=node01:2181,node02:2181,node03:2181/kafka
```
其它参数：
`advertised.listeners`: 主要用于 IaaS（Infrastructure as a Service）环境，比如公有云上的机器通常配备有多块网卡，即包含私网网卡和公网网卡，对于这种情况而言，可以设置 `advertised.listeners` 参数绑定公网 IP 供外部客户端使用，而配置 `listeners` 参数来绑定私网 IP 地址供 broker 间通信使用

`message.max.bytes`: broker 所能接收消息的最大值，默认值为 1000012B


## 环境测试
启动 kafka
```sh
kafka-server-start.sh server.properties
```
创建 topic
```sh
kafka-topics.sh --bootstrap-server node01:9092 --create --topic topic-demo --replication-factor 3 --partitions 10

kafka-topics.sh --zookeeper node01:2181 --create --topic topic-demo --replication-factor 3 --partitions 10
```
展示主题信息
```sh
kafka-topics.sh --bootstrap-server node01:9092 --describe --topic topic-demo
kafka-topics.sh --zookeeper node01:2181 --describe --topic topic-demo
```
```sh
kafka-topics.sh --bootstrap-server node01:9092 --list
kafka-topics.sh --zookeeper node01:9092 --list
```
```sh
kafka-console-producer.sh --broker-list node01:9092 --topic topic-demo
```
```sh
# --from-beginning
kafka-console-consumer.sh --bootstrap-server node01:9092 --topic topic-demo
```
