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






如果允许follower副本对外提供读服务（主写从读），首先会存在数据一致性的问题，消息从主节点同步到从节点需要时间，可能造成主从节点的数据不一致。主写从读无非就是为了减轻leader节点的压力，将读请求的负载均衡到follower节点，如果Kafka的分区相对均匀地分散到各个broker上，同样可以达到负载均衡的效果，没必要刻意实现主写从读增加代码实现的复杂程度


Consumer 使用拉模式从服务端拉取消息，并且保存消费的具体位置，当消费者宕机后恢复上线时可以根据之前保存的消费位置重新拉取需要的消息进行消费

分区中的所有副本统称为 AR（Assigned Replicas）
所有与 leader 副本保持一定程度同步的副本（包括 leader 副本在内）组成ISR（In-Sync Replicas）
与 leader 副本同步滞后过多的副本（不包括 leader 副本）组成 OSR（Out-of-Sync Replicas）

leader 副本负责维护和跟踪 ISR 集合中所有 follower 副本的滞后状态，当 follower 副本落后太多或失效时，leader 副本会把它从 ISR 集合中剔除。如果 OSR 集合中有 follower 副本“追上”了 leader 副本，那么 leader 副本会把它从 OSR 集合转移至 ISR 集合。默认情况下，当 leader 副本发生故障时，只有在 ISR 集合中的副本才有资格被选举为新的 leader，而在 OSR 集合中的副本则没有任何机会（不过这个原则也可以通过修改相应的参数配置来改变）

HW 即 High Watermark，标识了一个特定的消息偏移量（offset），消费者只能拉取到这个 offset 之前的消息

LEO 即 Log End Offset，标识当前日志文件中下一条待写入消息的 offset

分区 ISR 集合中的每个副本都会维护自身的 LEO，而 ISR 集合中最小的 LEO 即为分区的 HW



kafka 使用 zookeeper
topic 等配置管理
leader 选举
服务发现


