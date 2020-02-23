分区的划分不仅为 Kafka 提供了可伸缩性、水平扩展的功能，还通过多副本机制来为 Kafka 提供数据冗余以提高数据可靠性

Kafka 会在 log.dir 或 log.dirs 参数所配置的目录下创建相应的主题分区，默认情况下这个目录为 /tmp/kafka-logs/

一个分区只分布于一个 broker 上，对应一个文件夹，包含多个 segment。一个 segment 对应一个文件。记录之后被追加到 segment 中，不会被单独删除或者修改
清除过期日志时，直接删除一个或者多个 segment

Partitioner 决定生产者发送的消息到哪个 broker


## 分区数
分区是 Kafka 中最小的并行操作单元，对生产者而言，分区的数据写入是可以并行化的；对消费者而言，Kafka 只允许单个分区中的消息被一个消费者线程消费，一个消费组的消费并行度完全依赖于所消费的分区数

一味地增加分区数并不能使吞吐量一直得到提升，并且分区数也并不能一直增加，如果超过默认的配置值，还会引起 Kafka 进程的崩溃

增加一个分区，对应的增加了一个文件描述符。对于一个高并发、高性能的应用来说，可以适当调大文件描述符
```sh
ulimit -n 65535

ulimit -Hn
ulimit -Sn
```

也可以在 /etc/security/limits.conf 文件中设置，参考如下：
```
root soft nofile 65535
root hard nofile 65535
```

limits.conf 文件修改之后需要重启才能生效。limits.conf 文件与 ulimit 命令的区别在于前者是针对所有用户的，而且在任何 shell 中都是生效的，即与 shell 无关，而后者只是针对特定用户的当前 shell 的设定。在修改最大文件打开数时，最好使用 limits.conf 文件来修改。也可以通过在 /etc/profile 文件中添加 ulimit 的设置语句来使全局生效

目前 Kafka 只支持增加分区数而不支持减少分区数，减少主题的分区数，会报出 InvalidPartitionException 的异常。只所以不支持减少分区数，原因如下：
1. 被删除分区中的消息的处理：如果随着分区一起消失则消息的可靠性得不到保障；如果需要保留则又需要考虑如何保留。直接存储到现有分区的尾部，消息的时间戳就不会递增，如此对于 Spark、Flink 这类需要消息时间戳（事件时间）的组件将会受到影响；如果分散插入现有的分区，那么在消息量很大的时候，内部的数据复制会占用很大的资源，而且在复制期间，此主题的可用性难以保障
2. 顺序性问题、事务性问题，以及分区和副本的状态机切换问题
3. 这个功能的收益点很低

如果进行增加分区的操作，当主题中的消息包含 key 时（即 key 不为 null），根据 key 计算分区的行为就会受到影响，从而影响消息的顺序


## 分区重分配
当集群中的一个节点下线时，如果节点上的分区是单副本的，这些分区就不可用了，而且在节点恢复前，相应的数据也处于丢失状态；如果节点上的分区是多副本的，那么位于这个节点上的 leader 副本的角色会转交到集群的其他 follower 副本上。Kafka 不会将这些失效的分区副本自动地迁移到集群中剩余的可用 broker 节点上

当集群中新增 broker 节点时，只有新创建的主题分区才有可能被分配到这个节点上，而之前的主题分区并不会自动分配到新加入的节点中，这样新节点的负载和原先节点的负载之间严重不均衡

为了解决上述问题，Kafka 提供了 kafka-reassign-partitions.sh 脚本来执行分区重分配的工作，它可以在集群扩容、broker 节点失效的场景下对分区进行迁移

kafka-reassign-partitions.sh 脚本的使用分为 3 个步骤：
1. 创建需要一个包含主题清单的 JSON 文件
2. 根据主题清单和 broker 节点清单生成一份重分配方案
3. 根据这份方案执行具体的重分配动作。

```sh
kafka-topics.sh --bootstrap-server kafka-host:port --create --topic test_name --replication-factor 2 --partitions 4
```

由于某种原因，需要下线 brokerId 为 1 的 broker。节点，在此之前，将其上的分区副本迁移出去
```
{
    "topics":[
        {
            "topic":"test_name"
        }
    ],
    "version":1
}
```

根据这个 JSON 文件和指定所要分配的 broker 节点列表来生成一份候选的重分配方案

```sh
kafka-reassign-partitions.sh --bootstrap-server kafka-host:port --generate --topics-to-move-json-file reassign.json --broker-list 0,2
```

执行上述命令打印出了两个 JSON 格式的内容。第一个 Current partition replica assignment 所对应的 JSON 内容为当前的分区副本分配情况，在执行分区重分配的时候将这个内容保存起来，以备后续的回滚操作。第二个 Proposed partition reassignment configuration 所对应的 JSON 内容为重分配的候选方案

将第二个 JSON 内容保存在一个 JSON 文件中，假定这个文件的名称为 project.json

```sh
kafka-reassign-partitions.sh --bootstrap-server kafka-host:port --execute --reassignment-json-file project.json
```

可以查看主题中的所有分区副本都只在 0 和 2 的 broker 节点上分布了

分区重分配的基本原理是先通过控制器为每个分区添加新副本（增加副本因子），新的副本将从分区的 leader 副本那里复制所有的数据。复制完成之后，控制器将旧副本从副本清单里移除（恢复为原先的副本因子数）。需要注意的是，在重分配的过程中要确保有足够的空间

验证查看分区重分配的进度
```sh
kafka-reassign-partitions.sh --bootstrap-server kafka-host:port --verify --reassignment-json-file project.json
```

分区重分配对集群的性能有很大的影响，需要占用额外的资源，比如网络和磁盘。在实际操作中，我们将降低重分配的粒度，分成多个小批次来执行，以此来将负面的影响降到最低。还需要注意的是，如果要将某个 broker 下线，那么在执行分区重分配动作之前最好先关闭或重启 broker。这样这个 broker 就不再是任何分区的 leader 节点了，它的分区就可以被分配给集群中的其他 broker。这样可以减少 broker 间的流量复制，以此提升重分配的性能，以及减少对集群的影响


### 复制限流
数据复制会占用额外的资源，如果重分配的量太大会严重影响整体的性能，尤其是处于业务高峰期的时候。减小重分配的粒度，以小批次的方式来操作是一种可行的解决思路。但是，如果集群中某个主题或某个分区的流量在某段时间内特别大，那么只靠减小粒度是不足以应对的，这时就需要有一个限流的机制，对副本间的复制流量加以限制来保证重分配期间整体服务不会受太大的影响

副本间的复制限流有两种实现方式：kafka-config.sh 脚本和 kafka-reassign-partitions.sh 脚本

kafka-config.sh 脚本主要以动态配置的方式来达到限流的目的

在 broker 级别有两个与复制限流相关的配置参数：follower.replication.throttled.rate 和 leader.replication.throttled.rate，前者用于设置 follower 副本复制的速度，后者用于设置 leader 副本传输的速度，单位都是 B/s。通常情况下，两者的配置值是相同的

```sh
kafka-configs.sh --zookeeper localhost:2181/kafka --entity-type brokers --entity-name 1 --alter --add-config follower.replication.throttled.rate=1024,leader.replication.throttled.rate=1024
```
```sh
kafka-configs.sh --zookeeper localhost:2181/kafka --entity-type brokers --entity-name 1 --describe
```

在主题级别也有两个相关的参数来限制复制的速度：leader.replication.throttled.replicas 和 follower.replication.throttled.replicas，它们分别用来配置被限制速度的主题所对应的 leader 副本列表和 follower 副本列表

```sh
kafka-configs.sh --zookeeper localhost:2181/kafka --entity-type topics --entity-name customer-delete --alter --add-config leader.replication.throttled.replicas=[0:0,1:1,2:2],follower.replication.throttled.replicas=[0:1,1:2,2:0]
```

带有限流的分区重分配的用法
```
{
    "version":1,
    "partitions":[
        {
            "topic":"customer-delete",
            "partition":1,
            "replicas":[2,0],
            "log_dirs":["any","any"]
        },
        {
            "topic":"customer-delete",
            "partition":0,
            "replicas":[0,2],
            "log_dirs":["any","any"]
        },
        {
            "topic":"customer-delete",
            "partition":2,
            "replicas":[0,2],
            "log_dirs":["any","any"]
        }
    ]
}
```

如果分区重分配会引起某个分区 AR 集合的变更，那么这个分区中与 leader 有关的限制会应用于重分配前的所有副本，因为任何一个副本都可能是 leader，而与 follower 有关的限制会应用于所有移动的目的地

```sh
kafka-configs.sh --zookeeper localhost:2181/kafka --entity-type topics --entity-name customer-delete --alter --add-config leader.replication.throttled.replicas=[1:1,1:2,0:0,0:1],follower.replication.throttled.replicas=[1:0,0:2]
```

再设置 broker 2 的复制速度为 10B/s
```sh
kafka-configs.sh --zookeeper localhost:2181/kafka --entity-type brokers --entity-name 2 --alter --add-config follower.replication.throttled.rate=10,leader.replication.throttled.rate=10
```

```sh
kafka-reassign-partitions.sh --zookeeper localhost:2181/kafka --execute --reassignment-json-file project.json
```

配合 verify 参数，对临时设置的一些限制性的配置在使用完后进行删除
```sh
kafka-reassign-partitions.sh --zookeeper localhost:2181/kafka --verify --reassignment-json-file project.json
```

kafka-reassign-partitions.sh 脚本本身也提供了限流的功能

```sh
kafka-reassign-partitions.sh --zookeeper localhost:2181/kafka --execute --reassignment-json-file project.json --throttle 10
```

需要周期性地执行查看进度的命令直到重分配完成，这样可以确保限流设置被移除。也就是说，使用这种方式的限流同样需要显式地执行某些操作以使在重分配完成之后可以删除限流的设置

在重分配期间修改限制来增加吞吐量，以便完成得更快
```sh
kafka-reassign-partitions.sh --zookeeper localhost:2181/kafka --execute --reassignment-json-file project.json  --throttle 1024
```

推荐使用 kafka-reassign-partitions.sh 脚本配合 throttle 参数的方式进行复制限流