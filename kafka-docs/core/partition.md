## 主题与分区
### 创建主题
如果 broker 端配置参数 auto.create.topics.enable 设置为 true（默认值就是 true），那么当生产者向一个尚未创建的主题发送消息时，会自动创建一个分区数为 num.partitions（默认值为 1）、副本因子为 default.replication.factor（默认值为 1）的主题。除此之外，当一个消费者开始从未知主题中读取消息时，或者当任意一个客户端向未知主题发送元数据请求时，都会按照配置参数 num.partitions 和 default.replication.factor 的值来创建一个相应的主题。这种自动创建主题的行为都是非预期的，不建议将 auto.create.topics.enable 参数设置为 true

通过 kafka-topics.sh 脚本创建主题
```sh
kafka-topics.sh --zookeeper localhost:2181/kafka --create --topic customer-delete --partitions 4 --replication-factor 2
```

kafka-topics.sh 脚本实质上是调用了 kafka.admin.TopicCommand 类，通过向 TopicCommand 类中传入一些关键参数来实现主题的管理。也可以直接调用 TopicCommand 类中的 main() 函数来直接管理主题
```java
public static void createTopic() {
    String[] options = new String[]{
            "--zookeeper", "localhost:2181/kafka",
            "--create",
            "--replication-factor", "1",
            "--partitions", "1",
            "--topic", "topic-create-api"
    };
    kafka.admin.TopicCommand.main(options);
}
```

### 查看主题
```sh
kafka-topics.sh --zookeeper localhost:2181/kafka –list
```
```sh
kafka-topics.sh --zookeeper localhost:2181/kafka --describe --topic customer-delete,customer-create
```

增加 topics-with-overrides 参数列出包含了与集群不一样配置的主题
```sh
kafka-topics.sh --zookeeper localhost:2181/ kafka --describe --topics-with-overrides
```
增加 under-replicated-partitions 参数找出所有包含失效副本的分区。包含失效副本的分区可能正在进行同步操作，也有可能同步发生异常，此时分区的 ISR 集合小于 AR 集合。对于通过该参数查询到的分区要重点监控，因为这很可能意味着集群中的某个 broker 已经失效或同步效率降低等
```sh
kafka-topics.sh --zookeeper localhost:2181/kafka --describe --topic customer-delete --under-replicated-partitions
```
增加 unavailable-partitions 参数可以查看主题中没有 leader 副本的分区，这些分区已经处于离线状态，对于外界的生产者和消费者来说处于不可用的状态
```sh
kafka-topics.sh --zookeeper localhost:2181/kafka --describe --topic customer-delete --unavailable-partitions
```

### 修改主题
增加主题的分区数。以前面的主题 customer-delete 为例，当前分区数为 1，修改为 3，示例如下：
```sh
kafka-topics.sh --zookeeper localhost:2181/kafka --alter --topic customer-delete --partitions 10
```
当主题中的消息包含 key 时（即 key 不为 null），根据 key 计算分区的行为就会受到影响，从而影响消息的顺序

目前 Kafka 只支持增加分区数而不支持减少分区数，比如减少主题的分区数，就会报出 InvalidPartitionException 的异常

为什么不支持减少分区？
实现此功能需要考虑的因素很多，比如删除的分区中的消息该如何处理？如果随着分区一起消失则消息的可靠性得不到保障；如果需要保留则又需要考虑如何保留。直接存储到现有分区的尾部，消息的时间戳就不会递增，如此对于 Spark、Flink 这类需要消息时间戳（事件时间）的组件将会受到影响；如果分散插入现有的分区，那么在消息量很大的时候，内部的数据复制会占用很大的资源，而且在复制期间，此主题的可用性又如何得到保障？与此同时，顺序性问题、事务性问题，以及分区和副本的状态机切换问题都是不得不面对的。反观这个功能的收益点却是很低的，如果真的需要实现此类功能，则完全可以重新创建一个分区数较小的主题，然后将现有主题中的消息按照既定的逻辑复制过去即可。


我们还可以使用 kafka-topics.sh 脚本的 alter 指令来变更主题的配置
```sh
kafka-topics.sh --zookeeper 1ocalhost:2181/kafka --alter --topic customer-delete --config max.message.bytes=20000
```
还可以通过 delete-config 参数来删除之前覆盖的配置，使其恢复原有的默认值
```sh
kafka-topics.sh --zookeeper localhost:2181/ kafka --alter --topic customer-delete --delete-config max.message.bytes
```

使用 kafka-topics.sh 脚本的 alter 指令来变更主题配置的功能已经过时，推荐使用 kafka-configs.sh 脚本来实现相关功能

### 删除主题
删除主题可以释放一些资源，比如磁盘、文件句柄等
```sh
kafka-topics.sh --zookeeper localhost:2181/kafka --delete --topic customer-delete
```
必须将 delete.topic.enable 参数配置为 true 才能够删除主题，这个参数的默认值就是 true，如果配置为 false，那么删除主题的操作将会被忽略。建议将这个参数的值设置为 true

如果要删除的主题是 Kafka 的内部主题或者说主题不存在，那么删除时就会报错

脚本删除主题的行为本质上只是在 ZooKeeper 中的 /admin/delete_topics 路径下创建一个与待删除主题同名的节点，以此标记该主题为待删除的状态。与创建主题相同的是，真正删除主题的动作也是由 Kafka 的控制器负责完成的

我们可以直接通过 ZooKeeper 的客户端来删除主题
```sh
create /admin/delete_topics/customer-delete ""
```

我们还可以通过手动的方式来删除主题。主题中的元数据存储在 ZooKeeper 中的 /brokers/topics 和 /config/topics 路径下，主题中的消息数据存储在 log.dir 或 log.dirs 配置的路径下，只需要手动删除这些地方的内容即可
```sh
# 删除 ZooKeeper 中的节点 /config/topics/customer-delete
delete /config/topics/customer-delete
```
```sh
# 删除 ZooKeeper 中的节点 /brokers/topics/customer-delete 及其子节点
rmr /brokers/topics/customer-delete
```
```sh
# 删除集群中所有与主题 customer-delete 有关的文件

# 集群中的各个 broker 节点中执行
rm -rf /tmp/kafka-logs/customer-delete*
```

### 主题配置
在创建主题时可以通过 config 参数来设置所要创建主题的相关参数，通过这个参数可以覆盖原本的默认配置
```sh
kafka-topics.sh --zookeeper localhost:2181/kafka --create --topic customer-delete --replication-factor 1 --partitions 1 --config cleanup.policy=compact --config max.message.bytes=10000
```
设置 cleanup.policy 参数为 compact，以及 max.message.bytes 参数为 10000

可以通过 ZooKeeper 客户端查看所设置的参数，对应的 ZooKeeper 节点为 /config/topics/customer-delete
```
get /config/topics/topic-config
```

### 主题分区
Kafka 会在 log.dir 或 log.dirs 参数所配置的目录下创建相应的主题分区，默认情况下这个目录为 /tmp/kafka-logs/

我们不仅可以通过日志文件的根目录来查看集群中各个 broker 的分区副本的分配情况，还可以通过 ZooKeeper 客户端来获取。当创建一个主题时会在 ZooKeeper 的 /brokers/topics/ 目录下创建一个同名的实节点，该节点中记录了该主题的分区副本分配方案

```sh
get /brokers/topics/customer-delete
```
```
{"version":1,"partitions":{"2":[1,2],"1":[0,1],"3":[2,1],"0":[2,0]}}
```

还可以通过 describe 指令类型来查看分区副本的分配细节
```sh
kafka-topics.sh --zookeeper localhost:2181/kafka --describe --topic customer-delete
```

Kafka 从 0.10.x 版本开始支持指定 broker 的机架信息。如果指定了机架信息，则在分区副本分配时会尽可能地让分区副本分配到不同的机架上。指定机架信息是通过 broker 端参数 broker.rack 来配置的

如果一个集群中有部分 broker 指定了机架信息，并且其余的 broker 没有指定机架信息，那么在执行 kafka-topics.sh 脚本创建主题时会报出的 AdminOperationException 的异常

此时若要成功创建主题，要么将集群中的所有 broker 都加上机架信息或都去掉机架信息，要么使用 disable-rack-aware 参数来忽略机架信息，示例如下：
```sh
kafka-topics.sh --zookeeper localhost:2181/kafka --create --topic customer-delete -replication-factor 1 --partitions 1 --disable-rack-aware
```

如果集群中的所有 broker 都有机架信息，那么也可以使用 disable-rack-aware 参数来忽略机架信息对分区副本的分配影响


## 分区
分区的划分不仅为 Kafka 提供了可伸缩性、水平扩展的功能，还通过多副本机制来为 Kafka 提供数据冗余以提高数据可靠性

分区可以有一至多个副本，每个副本对应一个日志文件，每个日志文件对应一至多个日志分段（LogSegment），每个日志分段还可以细分为索引文件、日志存储文件和快照文件等

同一个分区中的多个副本必须分布在不同的 broker 中，这样才能提供有效的数据冗余

使用 kafka-topics.sh 脚本创建主题时的内部分配逻辑按照机架信息划分成两种策略：未指定机架信息和指定机架信息。如果集群中所有的 broker 节点都没有配置 broker.rack 参数，或者使用 disable-rack-aware 参数来创建主题，那么采用的就是未指定机架信息的分配策略，否则采用的就是指定机架信息的分配策略

当创建一个主题时，无论通过何种方式，实质上是在 ZooKeeper 中的 /brokers/topics 节点下创建与该主题对应的子节点并写入分区副本分配方案，并且在 /config/topics/ 节点下创建与该主题对应的子节点并写入主题相关的配置信息。而 Kafka 创建主题的实质性动作是交由控制器异步去完成的

我们可以直接使用 ZooKeeper 的客户端在 /brokers/topics 节点下创建相应的主题节点并写入预先设定好的分配方案，这样就可以创建一个新的主题了。这种创建主题的方式还可以绕过一些原本使用 kafka-topics.sh 脚本创建主题时的一些限制，比如分区的序号可以不用从 0 开始连续累加了
```sh
create /brokers/topics/customer-delete {"version":1,"partitions":{"2":[1,2],"1":[0,1],"3":[2,1],"0":[2,0]}}
```
```sh
create /brokers/topics/topic-create- special {"version":1,"partitions":{"10":[1,2],"21":[0,1],"33":[2,1],"40":[2,0]}}
```

### 优先副本
优先副本是指在 AR 集合列表中的第一个副本。理想情况下，优先副本就是该分区的 leader 副本，也就是 preferred leader。Kafka 要确保所有主题的优先副本在 Kafka 集群中均匀分布，这样就保证了所有分区的 leader 均衡分布

优先副本的选举是指通过一定的方式促使优先副本选举为 leader 副本，以此来促进集群的负载均衡，这一行为也可以称为分区平衡

分区平衡并不意味着 Kafka 集群的负载均衡，因为还要考虑集群中的分区分配是否均衡。每个分区的 leader 副本的负载也是各不相同的，有些 leader 副本的负载很高，而有些 leader 副本负载很低。也就是说，就算集群中的分区分配均衡、leader 分配均衡，也并不能确保整个集群的负载就是均衡的

Kafka 提供分区自动平衡的功能，与此对应的 broker 端参数是 auto.leader.rebalance.enable，该参数的默认值为 true。如果开启分区自动平衡的功能，则 Kafka 的控制器会启动一个定时任务，这个定时任务会轮询所有的 broker 节点，计算每个 broker 节点的分区不平衡率（broker 中的不平衡率 = 非优先副本的 leader 个数 / 分区总数）是否超过 leader.imbalance.per.broker.percentage 参数配置的比值，默认值为 10%，如果超过设定的比值则会自动执行优先副本的选举动作以求分区平衡。执行周期由参数 leader.imbalance.check.interval.seconds 控制，默认值为 300 秒，即 5 分钟

在生产环境中不建议将 auto.leader.rebalance.enable 设置为 true，因为这可能引起负面的性能问题，也有可能引起客户端一定时间的阻塞。因为执行的时间无法自主掌控，如果在关键时期执行优先副本的自动选举操作，势必会有业务阻塞、频繁超时之类的风险。而且，分区及副本的均衡也不能完全确保集群整体的均衡，并且集群中一定程度上的不均衡也是可以忍受的。可以针对此类相关的埋点指标设置相应的告警，在合适的时机执行合适的操作，也就是手动执行分区平衡

Kafka 中 kafka-perferred-replica-election.sh 脚本提供了对分区 leader 副本进行重新平衡的功能。优先副本的选举过程是一个安全的过程，Kafka 客户端可以自动感知分区 leader 副本的变更
```sh
kafka-preferred-replica-election.sh --zookeeper localhost:2181/kafka
```
```sh
kafka-topics.sh --zookeeper localhost:2181/kafka --describe --topic customer-delete
```
在脚本执行之后，主题 customer-delete 中的所有的优先副本都成为 leader 副本

leader 副本的转移是一项高成本的工作，如果要执行的分区数很多，那么必然会对客户端造成一定的影响。如果集群中包含大量的分区，那么使用上面的方法有可能会失效。在优先副本的选举过程中，具体的元数据信息会被存入 ZooKeeper 的 /admin/preferred_replica_election 节点，如果这些数据超过了 ZooKeeper 节点所允许的大小，那么选举就会失败。默认情况下 ZooKeeper 所允许的节点数据大小为 1MB

kafka-perferred-replica-election.sh 脚本中还提供了 path-to-json-file 参数来小批量地对部分分区执行优先副本的选举操作。通过 path-to-json-file 参数来指定一个 JSON 文件，这个 JSON 文件里保存需要执行优先副本选举的分区清单

只对主题 customer-delete 执行优先副本的选举操作，那么先创建一个 JSON 文件，文件名为 election.json，文件内容如下：
```
{
    "partitions":[
        {
            "partition":0,
            "topic":"topic-partitions"
        },
        {
            "partition":1,
            "topic":"topic-partitions"
        },
        {
            "partition":2,
            "topic":"topic-partitions"
        }
    ]
}
```

```
kafka-preferred-replica-election.sh --zookeeper localhost:2181/kafka --path-to-json-file election.json
```

在实际生产环境中，一般使用 path-to-json-file 参数来分批、手动地执行优先副本的选举操作。尤其是在应对大规模的 Kafka 集群时，理应杜绝采用非 path-to-json-file 参数的选举操作方式。同时，优先副本的选举操作也要注意避开业务高峰期，以免带来性能方面的负面影响


### 分区重分配
当集群中的一个节点下线时，如果节点上的分区是单副本的，这些分区就不可用了，而且在节点恢复前，相应的数据也处于丢失状态；如果节点上的分区是多副本的，那么位于这个节点上的 leader 副本的角色会转交到集群的其他 follower 副本上。Kafka 不会将这些失效的分区副本自动地迁移到集群中剩余的可用 broker 节点上

当集群中新增 broker 节点时，只有新创建的主题分区才有可能被分配到这个节点上，而之前的主题分区并不会自动分配到新加入的节点中，这样新节点的负载和原先节点的负载之间严重不均衡

为了解决上述问题，Kafka 提供了 kafka-reassign-partitions.sh 脚本来执行分区重分配的工作，它可以在集群扩容、broker 节点失效的场景下对分区进行迁移

kafka-reassign-partitions.sh 脚本的使用分为3个步骤：首先创建需要一个包含主题清单的 JSON 文件，其次根据主题清单和 broker 节点清单生成一份重分配方案，最后根据这份方案执行具体的重分配动作。

```sh
kafka-topics.sh --zookeeper localhost:2181/kafka --create --topic customer-delete --replication-factor 2 --partitions 4
```

由于某种原因，需要下线 brokerId 为 1 的 broker。节点，在此之前，将其上的分区副本迁移出去
```
{
    "topics":[
        {
            "topic":"customer-delete"
        }
    ],
    "version":1
}
```

根据这个 JSON 文件和指定所要分配的 broker 节点列表来生成一份候选的重分配方案

```sh
kafka-reassign-partitions.sh --zookeeper localhost:2181/kafka --generate --topics-to-move-json-file reassign.json --broker-list 0,2
```

执行上述命令打印出了两个 JSON 格式的内容。第一个 Current partition replica assignment 所对应的 JSON 内容为当前的分区副本分配情况，在执行分区重分配的时候将这个内容保存起来，以备后续的回滚操作。第二个 Proposed partition reassignment configuration 所对应的 JSON 内容为重分配的候选方案

将第二个 JSON 内容保存在一个 JSON 文件中，假定这个文件的名称为 project.json

```sh
kafka-reassign-partitions.sh --zookeeper localhost:2181/kafka --execute --reassignment-json-file project.json
```

可以查看主题中的所有分区副本都只在 0 和 2 的 broker 节点上分布了

分区重分配的基本原理是先通过控制器为每个分区添加新副本（增加副本因子），新的副本将从分区的 leader 副本那里复制所有的数据。复制完成之后，控制器将旧副本从副本清单里移除（恢复为原先的副本因子数）。需要注意的是，在重分配的过程中要确保有足够的空间

验证查看分区重分配的进度
```sh
kafka-reassign-partitions.sh --zookeeper localhost:2181/kafka --verify --reassignment-json-file project.json
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


### 副本因子
```
{
    "version": 1,
    "partitions": [
        {
            "topic": "topic-throttle",
            "partition": 1,
            "replicas": [2, 1, 0],
            "log_dirs": ["any", "any", "any"]
        },
        {
            "topic": "topic-throttle",
            "partition": 0,
            "replicas": [0, 2, 1],
            "log_dirs": ["any", "any", "any"]
        },
        {
            "topic": "topic-throttle",
            "partition": 2,
            "replicas": [0, 2, 1],
            "log_dirs": ["any", "any", "any"]
        }
    ]
}
```
```sh
kafka-reassign-partitions.sh --zookeeper localhost:2181/kafka --execute --reassignment-json-file add.json
```

与修改分区数不同的是，副本数可以减少，最直接的方式是关闭一些 broker，但这种做法不太正规。可以通过 kafka-reassign-partition.sh 脚本来减少分区的副本因子

```
{
    "version": 1,
    "partitions": [
        {
            "topic": "topic-throttle",
            "partition": 1,
            "replicas": [0],
            "log_dirs": ["any"]
        },
        {
            "topic": "topic-throttle",
            "partition": 0,
            "replicas": [1],
            "log_dirs": ["any"]
        },
        {
            "topic": "topic-throttle",
            "partition": 2,
            "replicas": [2],
            "log_dirs": ["any"]
        }
    ]
}
```
```sh
kafka-topics.sh --zookeeper localhost:2181/kafka --describe --topic customer-delete
```


### 分区数
向一个只有 1 个分区和 1 个副本的主题中发送 100 万条消息，并且每条消息大小为 1024B，生产者对应的 acks 参数为 1

```sh
kafka-producer-perf-test.sh --topic customer-delete --num-records 1000000 --record-size 1024 --throughput -1 --producer-props bootstrap.servers=localhost:9092 acks=1
```
throughput 参数用来进行限流控制，当设定的值小于 0 时不限流，当设定的值大于 0 时，如果发送的吞吐量大于该值时就会被阻塞一段时间

kafka-producer-perf-test.sh 脚本中有一个参数 print-metrics，指定了这个参数时会在测试完成之后打印指标信息

```sh
kafka-consumer-perf-test.sh --topic customer-delete --messages 1000000 --broker-list localhost:9092
```

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


## 副本机制
副本机制有以下优势：
1. 提供数据冗余。即使系统部分组件失效，系统依然能够继续运转，增加了整体可用性以及数据持久性
2. 提供高伸缩性。支持横向扩展，能够通过增加机器的方式来提升读性能，进而提高读操作吞吐量
3. 改善数据局部性。允许将数据放入与用户地理位置相近的地方，从而降低系统延时

对于 Apache Kafka 而言，目前只能享受到副本机制带来的第 1 个好处，也就是提供数据冗余实现高可用性和高持久性

### 副本角色
Kafka 采用基于领导者（Leader-based）的副本机制

1. 在 Kafka 中，副本分成两类：领导者副本（Leader Replica）和追随者副本（Follower Replica）。每个分区在创建时都要选举一个领导者副本，其余的副本自动称为追随者副本

2. 在 Kafka 中，追随者副本不对外提供服务。这就是说，所有的读写请求都必须 发往领导者副本所在的 Broker，由该 Broker 负责处理。追随者副本唯一的任务就是从领导者副本异步拉取消息，并写入到自己的提交日志中，从而实现与领导者副本的同步

3. 当领导者副本所在的 Broker 宕机时，Kafka 依托于 ZooKeeper 提供的监控功能实时感知到，并立即开启新一轮的领导者选举，从追随者副本中选一个作为新的领导者。当原 Leader 副本重启回来后，只能作为追随者副本加入到集群中

这种副本机制有以下优势：
1. 当生产者 向 Kafka 成功写入消息后，消费者马上就能读取刚才生产的消息。如果允许追随者副本对外提供服务，由于副本同步是异步的，因此有可能出现追随者副本还没有从领导者副本那里拉取到最新的消息，从而使得客户端看不到最新写入的消息

2. 方便实现单调读，如果允许追随者副本提供读服务，可能会看到这样的现象：第一次消费时看到的最新消息在第二次消费时不见了

### In-sync Replicas（ISR）
Kafka 引入 ISR 副本集合，ISR 中的副本都是与 Leader 同步的副本。相反，不在 ISR 中的追随者副本就被认为是与 Leader 不同步的。Leader 副本天然就在 ISR 中

Kafka 判断 Follower 是否与 Leader 同步的标准就是 Broker 端参数 `replica.lag.time.max.ms`。这个参数的表示 Follower 副本能够落后 Leader 副本的最长时间间隔，默认值是 10 秒。因此，只要一个 Follower 副本落后 Leader 副本的时间不连续超过 10 秒，那么 Kafka 就认为该 Follower 副本与 Leader 是同步的，即使此时 Follower 副本中保存的消息明显少于 Leader 副本中的消息

如果 Follower 副本与 Leader 副本不同步，就会被踢出 ISR。当该副本追上 Leader 时，又能够重新被加回 ISR

### Unclean 领导者选举
ISR 可能出现为空的现象，Kafka 需要重新选举一个新的 Leader。此时如果选择非同步副本作为新 Leader，就可能出现数据的丢失。Broker 端参数 `unclean.leader.election.enable` 控制是否允许 Unclean 领导者选举

开启 Unclean 领导者选举可能会造成数据丢失，但好处是，它使得分区 Leader 副本一直存在，不至于停止对外提供服务，因此提升了高可用性。反之，禁止 Unclean 领导者选举的好处在于维护了数据的一致性，避免了消息丢失，但牺牲了高可用性

建议你不要开启 Unclean 领导者选举


## 请求处理
### 处理流程
1. Acceptor 线程采用轮询的方式将入站请求公平地发到所有网络线程中
2. 网络线程池，Broker 端参数 `num.network.threads` 用于调整网络线程池的线程数。默认值是 3，表示每台 Broker 启动时会创建 3 个网络线程处理客户端发送的请求
3. 网络线程拿到请求后，将其放入到一个共享请求队列中
4. IO 线程池，负责从共享请求队列中取出请求并处理。如果是 PRODUCE 生产请求，则将消息写入到底层的磁盘日志中；如果是 FETCH 请求，则从磁盘或页缓存中读取消息。Broker 端参数 `num.io.threads` 控制 IO 线程池中的线程数。默认值是 8，表示每台 Broker 启动后自动创建 8 个 IO 线程处理请求。如果机器上 CPU 资源非常充裕，可以调大该参数，允许更多的并发请求被同时处理
5. IO 线程处理完请求后，将生成的响应发送到响应队列中，由对应的网络线程负责将响应返还给客户端

### Purgatory 组件
用来缓存延时请求，即那些一时未满足条件不能立刻处理的请求。比如设置了 acks=all 的 PRODUCE 请求，该请求就必须等待 ISR 中所有副本都接收了消息后才能返回，此时处理该请求的 IO 线程就必须等待其他 Broker 的写入结果。当请求不能立刻处理时，它就会暂存在 Purgatory 中。稍后一旦满足了完成条件，IO 线程会继续处理该请求，并将 Response 放入对应网络线程的响应队列中

### 请求类型
在 Kafka 内部，除了客户端发送的 PRODUCE 请求和 FETCH 请求之外，还有很多执行其他操作的请求类型，比如负责更新 Leader 副本、Follower 副本以及 ISR 集合的 LeaderAndIsr 请求，负责勒令副本下线的 StopReplica 请求等。与 PRODUCE 和 FETCH 请求相比，这些请求有个明显的不同：它们不是数据类的请求，而是控制类的请求。Kafka 社区把 PRODUCE 和 FETCH 这类请求称为数据类请求，把 LeaderAndIsr、StopReplica 这类请求称为控制类请求

社区于 2.3 版本正式实现了数据类请求和控制类请求的分离。Kafka Broker 启动后，会在后台分别两套创建网络线程池和 IO 线程池，它们分别处理数据类请求和控制类请求









## 控制器
主要作用是在 ZooKeeper 的帮助下管理和协调整个 Kafka 集群。集群中任意一台 Broker 都能充当控制器的角色，但是，在运行过程中，只能有一个 Broker 成为控制器，行使其管理和协调的职责。可以通过名为 activeController 的 JMX 指标实时监控控制器的存活状态

Broker 在启动时，会尝试去 ZooKeeper 中创建 /controller 节点。Kafka 当前选举控制器的规则是：第一个成功创建 /controller 节点的 Broker 会被指定为控制器

控制器的职责大致可以分为 5 种
1. 主题管理（创建、删除、增加分区）

2. 分区重分配
分区重分配主要是指对已有主题分区进行细粒度的分配功能

3. Preferred 领导者选举
Kafka 为了避免部分 Broker 负载过重而提供的一种换 Leader 的方案

4. 集群成员管理（新增 Broker、Broker 主动关闭、Broker 宕机）
控制器组件会利用 Watch 机制检查 ZooKeeper 的 /brokers/ids 节点下的子节点数量变更

5. 数据服务
向其他 Broker 提供数据服务。控制器上保存了最全的集群元数据信息，其他所有 Broker 会定期接收控制器发来的元数据更。这里面比较重要的数据有：
所有主题信息，包括具体的分区信息，比如领导者副本，ISR 集合中的副本等；所有 Broker 信息，包括当前运行中的 Broker，正在关闭中的 Broker 等；所有涉及运维任务的分区，包括当前正在进行 Preferred 领导者选举以及分区重分配的分区列表

值得注意的是，这些数据其实在 ZooKeeper 中也保存了一份。每当控制器初始化时，它都会从 ZooKeeper 上读取对应的元数据并填充到自己的缓存中


## 水位
### 高水位
高水位的作用:
1. 定义消息可见性，即标识分区下的哪些消息是可以被消费者消费的
2. 帮助 Kafka 完成副本同步

在分区高水位以下的消息被认为是已提交消息，反之就是未提交消息（位移值等于高水位的消息也属于未提交消息）。消费者只能消费已提交消息

### LEO
日志末端位移（Log End Offset）LEO 表示副本写入下一条消息的位移值。介于高水位和 LEO 之间的消息就属于未提交消息。Kafka 所有副本都有对应的高水位和 LEO 值，Leader 副本的高水位即为分区的高水位

在 Leader 副本所在的 Broker 上，还保存了其他 Follower 副本的 LEO 值。它们的主要作用是，帮助 Leader 副本确定其高水位，也就是分区高水位

### 水位更新
#### Leader 副本
处理生产者请求的逻辑：
1. 写入消息到本地磁盘，更新 LEO 值
2. 获取 Leader 副本所在 Broker 端保存的所有远程副本（与 Leader 副本同步） 的 LEO 值: LEO{0-n}
3. 获取 Leader 副本高水位值：currentHW
4. 更新 currentHW = max(currentHW, min(LEO{0-n}))

处理 Follower 副本拉取消息的逻辑：
1. 读取磁盘（或页缓存）中的消息数据
2. 使用 Follower 副本发送请求中的位移（从该位移开始拉取消息）值更新远程副本 LEO 值
3. 更新分区高水位值（具体步骤与处理生产者请求的步骤相同）

#### Follower 副本
从 Leader 拉取消息的处理逻辑：
1. 写入消息到本地磁盘，更新 LEO 值
2. 获取 Leader 发送的高水位值：currentHW
3. 获取更新过的 LEO 值：currentLEO
4. 更新高水位为 min(currentHW, currentLEO)

### 副本同步
#### 同步条件
1. 远程 Follower 副本在 ISR 中
2. 远程 Follower 副本 LEO 值落后于 Leader 副本 LEO 值的时间，不超过 Broker 端参数 `replica.lag.time.max.ms` 的值。如果使用默认值的话，就是不超过 10 秒

#### 同步示例
T1 时刻
leader: HW = 0, LEO = 0, Remote LEO = 0
follower: HW = 0, LEO = 0

T2 时刻，生产者给主题分区发送一条消息后
leader: HW = 0, LEO = 1, Remote LEO = 0
follower: HW = 0, LEO = 0

T3 时刻，Follower 尝试从 Leader 拉取消息（fetchOffset = 0），Leader 返回当前高水位
leader: HW = 0, LEO = 1, Remote LEO = 0
follower: HW = 0, LEO = 1

T4 时刻，Follower 尝试从 Leader 拉取消息（fetchOffset = 1），Leader 返回当前高水位
leader: HW = 1, LEO = 1, Remote LEO = 1
follower: HW = 1, LEO = 1

### Leader Epoch
Leader 副本高水位更新和 Follower 副本高水位更新在时间上是存在错配的，可能会导致数据丢失或者数据不一致。为避免出现这种情况，引入 Leader Epoch

Leader Epoch 由两部分数据组成
1. Epoch，一个单调增加的版本号。每当副本领导权发生变更时，都会增加该版本号。小版本号的 Leader 被认为是过期 Leader，不能再行使 Leader 权力
2. 起始位移（Start Offset）。Leader 副本在该 Epoch 值上写入的首条消息的位移

Kafka Broker 会在内存中为每个分区都缓存 Leader Epoch 数据，同时定期地将这些信息持久化到一个 checkpoint 文件中。当 Leader 副本写入消息到磁盘时，Broker 会尝试更新这部分缓存。如果该 Leader 是首次写入消息，那么 Broker 会向缓存中增加一个 Leader Epoch 条目，否则就不做更新。这样，每次有 Leader 变更时，新的 Leader 副本会查询这部分缓存，取出对应的 Leader Epoch 的起始位移，以避免数据丢失和不一致的情况