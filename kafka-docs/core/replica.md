## 副本分配
分区可以有一至多个副本，每个副本对应一个日志文件，每个日志文件对应一至多个日志分段（LogSegment），每个日志分段还可以细分为索引文件、日志存储文件和快照文件等

同一个分区中的多个副本必须分布在不同的 broker 中，这样才能提供有效的数据冗余

### 副本分配策略
Kafka 从 0.10.x 版本开始支持指定 broker 的机架信息。使用 kafka-topics.sh 脚本创建主题时的内部分配逻辑按照机架信息划分成两种策略：
1. 如果集群中所有的 broker 节点都没有配置 broker.rack 参数，或者使用 disable-rack-aware 参数来创建主题，那么采用的就是未指定机架信息的分配策略
2. 如果集群中所有的 broker 节点都配置 broker.rack 参数，则采用指定机架信息的分配策略
3. 如果集群中有部分 broker 指定了机架信息，并且其余的 broker 没有指定机架信息，那么在执行 kafka-topics.sh 脚本创建主题时会报出的 AdminOperationException 的异常

可以使用 disable-rack-aware 参数来忽略机架信息对分区副本的分配影响
```sh
kafka-topics.sh --bootstrap-server broker_host:port --create --topic topic_name --replication-factor 1 --partitions 1 --disable-rack-aware
```

当创建一个主题时，无论通过何种方式，实质上是在 ZooKeeper 中的 /brokers/topics 节点下创建与该主题对应的子节点并写入分区副本分配方案，并且在 /config/topics/ 节点下创建与该主题对应的子节点并写入主题相关的配置信息。而 Kafka 创建主题的实质性动作是交由控制器异步去完成的

我们可以直接使用 ZooKeeper 的客户端在 /brokers/topics 节点下创建相应的主题节点并写入预先设定好的分配方案，这样就可以创建一个新的主题了
```sh
create /brokers/topics/topic_name {"version":1,"partitions":{"2":[1,2],"1":[0,1],"3":[2,1],"0":[2,0]}}
```
这种创建主题的方式还可以绕过一些原本使用 kafka-topics.sh 脚本创建主题时的一些限制，比如分区的序号可以不用从 0 开始连续累加了
```sh
create /brokers/topics/topic_name {"version":1,"partitions":{"10":[1,2],"21":[0,1],"33":[2,1],"40":[2,0]}}
```

### 查看副本分配
我们不仅可以通过日志文件的根目录来查看集群中各个 broker 的分区副本的分配情况，还可以通过 ZooKeeper 客户端来获取
```sh
# 主题的分区副本分配方案
get /brokers/topics/topic_name
```
```
{"version":1,"partitions":{"2":[1,2],"1":[0,1],"3":[2,1],"0":[2,0]}}
```

还可以通过 describe 指令类型来查看分区副本的分配细节
```sh
kafka-topics.sh --bootstrap-server broker_host:port --describe --topic topic_name
```


## 副本增减
```
{
    "version": 1,
    "partitions": [
        {
            "topic": "topic_name",
            "partition": 1,
            "replicas": [2, 1, 0],
            "log_dirs": ["any", "any", "any"]
        },
        {
            "topic": "topic_name",
            "partition": 0,
            "replicas": [0, 2, 1],
            "log_dirs": ["any", "any", "any"]
        },
        {
            "topic": "topic_name",
            "partition": 2,
            "replicas": [0, 2, 1],
            "log_dirs": ["any", "any", "any"]
        }
    ]
}
```
```sh
kafka-reassign-partitions.sh --bootstrap-server broker_host:port --execute --reassignment-json-file add.json
```

与修改分区数不同的是，副本数可以减少，最直接的方式是关闭一些 broker，但这种做法不太正规。可以通过 kafka-reassign-partition.sh 脚本来减少分区的副本因子

```
{
    "version": 1,
    "partitions": [
        {
            "topic": "topic_name",
            "partition": 1,
            "replicas": [0],
            "log_dirs": ["any"]
        },
        {
            "topic": "topic_name",
            "partition": 0,
            "replicas": [1],
            "log_dirs": ["any"]
        },
        {
            "topic": "topic_name",
            "partition": 2,
            "replicas": [2],
            "log_dirs": ["any"]
        }
    ]
}
```


## 副本机制
副本机制有以下优势：
1. 提供数据冗余。即使系统部分组件失效，系统依然能够继续运转，增加了整体可用性以及数据持久性
2. 提供高伸缩性。支持横向扩展，能够通过增加机器的方式来提升读性能，进而提高读操作吞吐量
3. 改善数据局部性。允许将数据放入与用户地理位置相近的地方，从而降低系统延时

对于 Apache Kafka 而言，目前只能享受到副本机制带来的第 1 个好处，也就是提供数据冗余实现高可用性和高持久性

### 副本角色
Kafka 采用基于领导者（Leader-based）的副本机制

1. 在 Kafka 中，副本分成两类：领导者副本（Leader Replica）和追随者副本（Follower Replica）。每个分区在创建时都要选举一个领导者副本，其余的副本自动称为追随者副本

2. 在 Kafka 中，追随者副本不对外提供服务。这就是说，所有的读写请求都必须发往领导者副本所在的 Broker，由该 Broker 负责处理。追随者副本唯一的任务就是从领导者副本异步拉取消息，并写入到自己的提交日志中，从而实现与领导者副本的同步

3. 当领导者副本所在的 Broker 宕机时，Kafka 依托于 ZooKeeper 提供的监控功能实时感知到，并立即开启新一轮的领导者选举，从追随者副本中选一个作为新的领导者。当原 Leader 副本重启回来后，只能作为追随者副本加入到集群中

这种副本机制有以下优势：
1. 当生产者向 Kafka 成功写入消息后，消费者马上就能读取刚才生产的消息。如果允许追随者副本对外提供服务，由于副本同步是异步的，因此有可能出现追随者副本还没有从领导者副本那里拉取到最新的消息，从而使得客户端看不到最新写入的消息

2. 方便实现单调读，如果允许追随者副本提供读服务，可能会看到这样的现象：第一次消费时看到的最新消息在第二次消费时不见了

### In-sync Replicas（ISR）
Kafka 引入 ISR 副本集合，ISR 中的副本都是与 Leader 同步的副本。相反，不在 ISR 中的追随者副本就被认为是与 Leader 不同步的。Leader 副本天然就在 ISR 中

Kafka 判断 Follower 是否与 Leader 同步的标准就是 Broker 端参数 `replica.lag.time.max.ms`。这个参数的表示 Follower 副本能够落后 Leader 副本的最长时间间隔，默认值是 10 秒。因此，只要一个 Follower 副本落后 Leader 副本的时间不连续超过 10 秒，那么 Kafka 就认为该 Follower 副本与 Leader 是同步的，即使此时 Follower 副本中保存的消息明显少于 Leader 副本中的消息

如果 Follower 副本与 Leader 副本不同步，就会被踢出 ISR。当该副本追上 Leader 时，又能够重新被加回 ISR

### Unclean 领导者选举
ISR 可能出现为空的现象，Kafka 需要重新选举一个新的 Leader。此时如果选择非同步副本作为新 Leader，就可能出现数据的丢失。Broker 端参数 `unclean.leader.election.enable` 控制是否允许 Unclean 领导者选举

开启 Unclean 领导者选举可能会造成数据丢失，但好处是，它使得分区 Leader 副本一直存在，不至于停止对外提供服务，因此提升了高可用性。反之，禁止 Unclean 领导者选举的好处在于维护了数据的一致性，避免了消息丢失，但牺牲了高可用性。建议不要开启 Unclean 领导者选举

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

leader 副本的转移是一项高成本的工作，如果要执行的分区数很多，那么必然会对客户端造成一定的影响。如果集群中包含大量的分区，那么使用上面的方法有可能会失效。在优先副本的选举过程中，具体的元数据信息会被存入 ZooKeeper 的 /admin/preferred_replica_election 节点，如果这些数据超过了 ZooKeeper 节点所允许的大小，那么选举就会失败。默认情况下 ZooKeeper 所允许的节点数据大小为 1MB

kafka-perferred-replica-election.sh 脚本中还提供了 path-to-json-file 参数来小批量地对部分分区执行优先副本的选举操作。通过 path-to-json-file 参数来指定一个 JSON 文件，这个 JSON 文件里保存需要执行优先副本选举的分区清单

只对主题 topic_name 执行优先副本的选举操作，那么先创建一个 JSON 文件，文件名为 election.json，文件内容如下：
```
{
    "partitions":[
        {
            "partition":0,
            "topic":"topic_name"
        },
        {
            "partition":1,
            "topic":"topic_name"
        },
        {
            "partition":2,
            "topic":"topic_name"
        }
    ]
}
```
```sh
kafka-preferred-replica-election.sh --zookeeper localhost:2181/kafka --path-to-json-file election.json
```

在实际生产环境中，一般使用 path-to-json-file 参数来分批、手动地执行优先副本的选举操作。尤其是在应对大规模的 Kafka 集群时，理应杜绝采用非 path-to-json-file 参数的选举操作方式。同时，优先副本的选举操作也要注意避开业务高峰期，以免带来性能方面的负面影响
