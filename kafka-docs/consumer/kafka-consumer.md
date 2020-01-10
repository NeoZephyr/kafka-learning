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


## TCP 连接
在调用 poll 方法时被创建的，而 poll 方法内部有 3 个时机可以创建 TCP 连接

### 创建连接
1. 发起 FindCoordinator 请求
当消费者程序首次启动调用 poll 方法时，它需要向 Kafka 集群（集群中的任意服务器）发送一个名为 FindCoordinator 的请求，以获取对应的协调者（驻留在 Broker 端的内存中，负责消费者组的组成员管理和各个消费者的位移提交管理）。然后，消费者会创建一个 Socket 连接

2. 连接协调者
Broker 处理完 FindCoordinator 请求之后，会返还对应的响应结果，告诉消费者哪个 Broker 是真正的协调者，因此，消费者知晓了真正的协调者后，会创建连向该 Broker 的 Socket 连接。只有成功连入协调者，协调者才能开启正常的组协调操作，比如加入组、等待组分配方案、心跳请求处理、位移获取、位移提交等

3. 消费数据
消费者会为每个要消费的分区创建与该分区领导者副本所在 Broker 连接的 TCP

需要注意的是，当第三类 TCP 连接成功创建后，消费者程序就会废弃第一类 TCP 连接，之后在定期请求元数据时，它会改为使用第三类 TCP 连接

### 关闭连接
1. 主动关闭
手动调用 KafkaConsumer.close() 方法，或者是执行 Kill 命令

2. 自动关闭
由消费者端参数 `connection.max.idle.ms` 控制，该参数默认值是 9 分钟，即如果某个 Socket 连接上连续 9 分钟都没有任何请求，那么消费者会强行杀掉这个 Socket 连接



