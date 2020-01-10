## 线程
生产者客户端由两个线程协调运行，这两个线程分别为主线程和 Sender 线程

### 主线程
在主线程中由 KafkaProducer 创建消息，然后通过拦截器、序列化器和分区器的作用之后缓存到消息累加器（RecordAccumulator，也称为消息收集器）中

### Sender 线程
Sender 线程从 RecordAccumulator 中的双端队列头部读取消息，然后将 `<分区, Deque<ProducerBatch>>` 的保存形式转变成 `<Node, List<ProducerBatch>>` 的形式。这是因为，生产者客户端只向具体的 broker 节点发送消息，并不关心消息属于哪一个分区；而 KafkaProducer 的应用逻辑只关注向哪个分区中发送消息

Sender 线程最终会将消息封装成 `<Node, Request>` 的形式，将请求发往各个 Node


## 存储
当一条消息（ProducerRecord）流入 RecordAccumulator 时，会先寻找与消息分区所对应的双端队列（如果没有则新建），再从这个双端队列的尾部获取一个 ProducerBatch（如果没有则新建），查看 ProducerBatch 中是否还可以写入这个 ProducerRecord，如果可以则写入，如果不可以则需要创建一个新的 ProducerBatch。在新建 ProducerBatch 时评估这条消息的大小是否超过 `batch.size` 参数的大小，如果不超过，那么就以 `batch.size` 参数的大小来创建 ProducerBatch，这样在使用完这段内存区域之后，可以通过 BufferPool 的管理来进行复用；如果超过，那么就以评估的大小来创建 ProducerBatch，这段内存区域不会被复用

### RecordAccumulator
RecordAccumulator 内部为每个分区都维护了一个双端队列，用来缓存消息以便 Sender 线程可以批量发送，进而减少网络传输的资源消耗以提升性能。当消息写入缓存时，追加到对应分区的双端队列的尾部

队列中的内容就是 ProducerBatch，ProducerBatch 中可以包含一至多个 ProducerRecord，这样可以使字节的使用更加紧凑。而且，将较小的 ProducerRecord 拼凑成一个较大的 ProducerBatch，也可以减少网络请求的次数以提升整体的吞吐量

如果生产者客户端需要向很多分区发送消息，则可以将 `buffer.memory` 参数适当调大以增加整体的吞吐量，该参数默认值为 32MB

如果生产者发送消息的速度超过发送到服务器的速度，则会导致生产者空间不足。此时，KafkaProducer 的 send() 方法调用要么被阻塞，要么抛出异常，这取决于参数 `max.block.ms` 的配置，该参数的默认值为 60 秒

### BufferPool
在 Kafka 生产者客户端中，通过 `java.io.ByteBuffer` 实现消息内存的创建和释放。在 RecordAccumulator 的内部还有一个 BufferPool，它主要用来实现 ByteBuffer 的复用，以实现缓存的高效利用

BufferPool 只针对特定大小的 ByteBuffer 进行管理，其他大小的 ByteBuffer 不会缓存进 BufferPool 中，这个特定的大小由 `batch.size` 参数来指定，默认值为 16KB。可以适当地调大 `batch.size` 参数以便多缓存一些消息

### InFlightRequests
请求在从 Sender 线程发往 Kafka 之前还会保存到 InFlightRequests 中，InFlightRequests 保存对象的具体形式为 `Map<NodeId, Deque>`，缓存了已经发出去但还没有收到响应的请求（NodeId 是一个 String 类型，表示节点的 id 编号）。可以通过配置参数 `max.in.flight.requests.per.connection` 限制每个连接（也就是客户端与 Node 之间的连接）最多缓存的请求数，默认值为 5，即每个连接最多只能缓存 5 个未响应的请求，超过该数值之后就不能再向这个连接发送更多的请求了，除非有缓存的请求收到了响应

通过 InFlightRequests 还可以获得 leastLoadedNode（负载最小 Node），也就是在 InFlightRequests 中还未确认请求最少的 Node。选择 leastLoadedNode 发送请求可以使它能够尽快发出，避免因网络拥塞等异常而影响整体的进度。leastLoadedNode 的概念可以用于多个应用场合，比如元数据请求、消费者组播协议的交互


## TCP 连接
### 创建
1. KafkaProducer 实例创建时启动 Sender 线程，从而与 `bootstrap.servers` 中所有 Broker 建立 TCP 连接。Producer 一旦连接到集群中的任一台 Broker，就能拿到整个集群的 Broker 信息

2. KafkaProducer 实例首次更新元数据信息之后，如果发现与某些 Broker 没有连接，那么它就会创建一个 TCP 连接

3. 如果 Producer 端发送消息到某台 Broker 时发现没有与该 Broker 的 TCP 连接，那么也会立即创建连接

#### 关闭
用户主动关闭：调用 `producer.close()` 方法关闭
Kafka 自动关闭：如果设置 Producer 端 `connections.max.idle.ms` 参数大于 0，则与 `bootstrap.servers` 中 Broker 建立的 TCP 连接会被自动关闭；如果设置该参数为 -1，那么与 `bootstrap.servers` 中 Broker 建立的 TCP 连接将成为永久长连接，从而成为僵尸连接。`connections.max.idle.ms` 参数默认为 9 分钟

Kafka 自动关闭，属于被动关闭的场景，会产生大量的 CLOSE_WAIT 连接，因此 Producer 端没有机会显式地观测到此连接已被中断


## 元数据更新
元数据具体记录集群中的主题、主题拥有的分区、每个分区的 leader 副本所在的节点、follower 副本所在的节点、AR、ISR 等集合中的副本、集群中的节点，控制器节点等信息

元数据更新场景：
1. 当 Producer 尝试给一个不存在的主题发送消息时，Broker 会告诉 Producer 说这个主题不存在。此时 Producer 会发送 METADATA 请求给 Kafka 集群，去尝试获取最新的元数据信息
2. 当超过 `metadata.max.age.ms` 时间没有更新元数据，不论集群那边是否有变化，Producer 都会发送 METADATA 请求给 Kafka 集群，强制刷新一次元数据以保证它是最及时的数据。参数`metadata.max.age.ms` 的默认值为 5 分钟

当需要更新元数据时，会先挑选出 leastLoadedNode，然后向这个 Node 发送 MetadataRequest 请求来获取具体的元数据信息。这个更新操作是由 Sender 线程发起的，在创建完 MetadataRequest 之后同样会存入 InFlightRequests，之后的步骤就和发送消息时的类似。元数据虽然由 Sender 线程负责更新，但是主线程也需要读取这些信息，这里的数据同步通过 synchronized 和 final 关键字来保障