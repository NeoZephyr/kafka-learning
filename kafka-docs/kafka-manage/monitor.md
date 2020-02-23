## 监控范围
### 主机监控
监控 Kafka 集群 Broker 所在的节点机器的性能

### JVM 监控
1. Full GC 发生频率和时长。长时间的停顿会令 Broker 端抛出各种超时异常
2. 活跃对象大小。这个指标是设定堆大小的重要依据
3. 应用线程总数

监控 Broker GC 日志，即以 kafkaServer-gc.log 开头的文件。一旦发现 Broker 进程频繁 Full GC，可以开启 G1 的 -XX:+PrintAdaptiveSizePolicy 开关，让 JVM 告诉你到底是谁引发了 Full GC

### 集群监控
#### 进程监控
Broker 进程是否启动，端口是否建立

#### 日志监控
服务器日志 server.log，控制器日志 controller.log，主题分区状态变更日志 state-change.log

#### 线程监控
1. Log Compaction 线程：以 kafka-log-cleaner-thread 开头，一旦挂掉，所有 Compaction 操作都会中断
2. 副本拉取消息的线程：以 ReplicaFetcherThread 开头，如果挂掉，则对应的 Follower 副本不再从 Leader 副本拉取消息

#### JMX 指标
在使用 JMX 之前需要确保 Kafka 开启了 JMX 的功能（默认关闭）。Kafka 在启动时需要通过配置 JMX_PORT 来设置 JMX 的端口号并以此来开启 JMX 的功能
```sh
JMX_PORT=9999

nohup kafka-server-start.sh server.properties &
```
开启 JMX 之后会在 ZooKeeper 的 /brokers/ids/<brokerId> 节点中有对应的呈现

1. BytesIn/BytesOut：Broker 端每秒入站和出站字节数，确保这组值不要接近网络带宽
2. NetworkProcessorAvgIdlePercent：网络线程池线程平均的空闲比例，通常应该确保这个 JMX 值长期大于 30%
3. RequestHandlerAvgIdlePercent：I/O 线程池线程平均的空闲比例，该值应长期小于 30%
4. UnderReplicatedPartitions：未充分备份的分区数，即并非所有的 Follower 副本都和 Leader 副本保持同步，表明分区有可能会出现数据丢失
5. ISRShrink/ISRExpand：即 ISR 收缩和扩容的频次指标
6. ActiveControllerCount：即当前处于激活状态的控制器的数量。正常情况下，Controller 所在 Broker 上的这个 JMX 指标值应该是 1，其他 Broker 上的这个值是 0。如果发现存在多台 Broker 上该值都是 1 的情况，一定要赶快处理，处理方式主要是查看网络连通性。这种情况通常表明集群出现了脑裂

#### 客户端
##### 生产者
1. 以 kafka-producer-network-thread 开头的线程负责实际消息发送，需要时刻关注
2. 关注 JMX 指标 request-latency，即消息生产请求的延时。这个 JMX 最直接地表征了 Producer 程序的 TPS

##### 消费者
1. 以 kafka-coordinator-heartbeat-thread 开头心跳线程也是必须要监控的一个线程
2. records-lag 和 records-lead 是两个重要的 JMX 指标。它们直接反映了 Consumer 的消费进度。如果使用了 Consumer Group，还需要关注 join rate 和 sync rate，它们说明了 Rebalance 的频繁程度。如果它们的值很高，那么就需要思考下 Rebalance 频繁发生的原因


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