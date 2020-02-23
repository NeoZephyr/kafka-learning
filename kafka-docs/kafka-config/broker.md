## log.dirs
指定了 Broker 需要使用的若干个文件目录路径。在生产环境中一般为 log.dirs 配置多个路径，使用用逗号进行分隔。如果有条件，最好保证这些目录挂载到不同的物理磁盘上。这样做有两个好处：
1. 提升读写性能：比起单块磁盘，多块物理磁盘同时读写数据有更高的吞吐量
2. 能够实现故障转移：自 1.1 开始，坏掉的磁盘上的数据会自动地转移到其他正常的磁盘上，而且 Broker 还能正常工作


## zookeeper.connect
zookeeper 负责协调管理并保存 Kafka 集群的所有元数据信息：比如集群中运行的 Broker、创建的 Topic、每个 Topic 的分区、分区的 Leader 副本信息等
```
zk1:2181,zk2:2181,zk3:2181/kafka
```


## listeners
监听器，告诉外部连接者要通过什么协议访问指定主机名和端口开放的 Kafka 服务


## advertised.listeners
声明用于用于对外发布的监听器


## auto.create.topics.enable
是否允许自动创建 Topic，默认值为 true，建议设置成 false，即不允许自动创建 Topic


## unclean.leader.election.enable
是否允许 Unclean Leader 选举，建议设置成 false

如果设置成 false，就防止那些落后太多的副本竞选 Leader。这样的话，当保存数据比较多的副本都挂了的话，这个分区也就不可用了，因为没有 Leader 了。如果设置为 true，允许从那些落后太多的副本中选一个出来当 Leader。这样的话，数据就有可能丢失，因为这些副本保存的数据本来就不全


## auto.leader.rebalance.enable
是否允许定期进行 Leader 选举，建议设置成 false

设置为 true 表示允许定期地对一些 Topic 分区进行 Leader 重选举。即在满足一定的条件的情况下。更换换 Leader。比如 Leader A 一直表现得很好，但若该参数设置为 true，那么有可能一段时间后 Leader A 就要被强行卸任换成 Leader B。换一次 Leader 代价很高，原本向 A 发送请求的所有客户端都要切换成向 B 发送请求，而且这种换 Leader 本质上没有任何性能收益


## log.retention.{hour|minutes|ms}
控制一条消息数据被保存多长时间。从优先级上来说 ms 设置最高、minutes 次之、hour 最低。通常情况下设置 hour 级别的多一些，比如 log.retention.hour=168 表示默认保存 7 天的数据，自动删除 7 天前的数据


## log.retention.bytes
指定 Broker 为消息保存的总磁盘容量大小

这个值默认是 -1，表示在这台 Broker 上保存数据大小不做限制。当在云上构建多租户的 Kafka 集群，限制每个租户使用的磁盘空间，可以使用这个参数


## message.max.bytes
控制 Broker 能够接收的最大消息大小

默认值为 1000012，还不到 1MB。实际场景中突破 1MB 的消息都是屡见不鲜的，因此在线上环境中设置一个比较大的值还是比较保险的做法。毕竟它只是一个标尺而已，仅仅衡量 Broker 能够处理的最大消息大小，即使设置大一点也不会耗费什么磁盘空间

