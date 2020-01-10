## Rebalance
Rebalance 就是让一个 Consumer Group 下所有的 Consumer 实例就如何消费订阅主题的所有分区达成共识的过程

### 触发条件
#### 组成员数发生变更
比如有新的 Consumer 实例加入组或者离开组，抑或是有 Consumer 实例崩溃被踢出

#### 订阅主题数发生变更
Consumer Group 可以使用正则表达式的方式订阅主题，比如 `consumer.subscribe(Pattern.compile("t.*c"))`。在 Consumer Group 的运行过程中，当创建一个满足条件的主题后，就会发生 Rebalance

#### 订阅主题的分区数发生变更
Kafka 当前只能允许增加一个主题的分区数。当分区数增加时，就会触发订阅该主题的所有 Group 开启 Rebalance

### Rebalance 流程
重平衡的通知机制是通过心跳线程来完成的。当协调者开启重平衡后，它会将 REBALANCE_IN_PROGRESS 封装进心跳请求的响应中，发还给消费者实例。当消费者实例发现心跳响应中包含了 REBALANCE_IN_PROGRESS，就知道重平衡开始了。消费者端参数 `heartbeat.interval.ms` 表面上是设置了心跳的间隔时间，但实际作用是控制重平衡通知的频率

消费者组状态
1. Empty 状态，组内没有任何成员，但消费者可能存在已提交的位移数据，而且这些位移数据尚未过期
2. Dead 状态，组内没有任何组员，但组的元数据信息已经在协调者端被移除
3. PreparingRebalance 状态，消费者准备开启重平衡，此时所有成员都要重新请求加入消费者组
4. CompletingRebalance 状态，消费者组下所有成员已经加入，各个成员正在等待分配方案
5. Stable 状态，表明重平衡已经完成，组内各成员能够正常消费数据了

消费者组状态流转
1. 消费者组最开始是 Empty 状态
2. 重平衡开启，变为 PreparingRebalance 状态
3. 组成员加入，变为 CompletingRebalance 状态，等待分配方案
4. Leader 完成分配，流转到 Stable 状态，完成重平衡
5. 当有新成员加入或已有成员退出时，消费者组的状态从 Stable 直接跳到 PreparingRebalance 状态，此时，所有现存成员就必须重新申请加入组。如果所有成员都退出组后，消费者组状态变更为 Empty

Kafka 定期自动删除过期位移的条件就是，消费者组要处于 Empty 状态。因此，如果消费者组停掉了很长时间，那么 Kafka 很可能就把该组的位移数据删除了

#### 消费者端
加入组：当组内成员加入组时，它会向协调者发送 JoinGroup 请求。在该请求中，每个成员都要将自己订阅的主题上报，这样协调者就能收集到所有成员的订阅信息。一旦收集了全部成员的 JoinGroup 请求后，协调者会从这些成员中选择一个担任这个消费者组的领导者。通常情况下，第一个发送 JoinGroup 请求的成员自动成为领导者。选出领导者之后，协调者会把消费者组订阅信息封装进 JoinGroup 请求的响应体中，然后发给领导者，由领导者制定具体的分区消费分配方案

领导者消费者分配方案：领导者向协调者发送 SyncGroup 请求，将分配方案发给协调者。值得注意的是，其他成员也会向协调者发送 SyncGroup 请求，只不过请求体中并没有实际的内容。这一步的主要目的是让协调者接收分配方案，然后统一以 SyncGroup 响应的方式分发给所有成员，这样组内所有成员就都知道自己该消费哪些分区了

#### Broker 端
1. 新成员入组
新成员入组是指组处于 Stable 状态后，有成员加入。当协调者收到新的 JoinGroup 请求后，它会通过心跳请求响应的方式通知组内现有的所有成员，强制它们开启新一轮的重平衡

2. 组成员主动离组
消费者实例所在线程或进程调用 `close()` 方法主动通知协调者它要退出。协调者收到 LeaveGroup 请求后，依然会以心跳响应的方式通知其他成员

3. 组成员崩溃离组
崩溃离组是指消费者实例出现严重故障，突然宕机导致的离组。和主动离组不同，协调者通常需要等待一段时间才能感知到，这段时间一般是由消费者端参数 `session.timeout.ms` 控制的

4. 重平衡时协调者对组内成员提交位移的处理
正常情况下，每个组内成员都会定期汇报位移给协调者。当重平衡开启时，协调者会给予成员一段缓冲时间，要求每个成员必须在这段时间内快速地上报自己的位移信息，然后再开启正常的 JoinGroup/SyncGroup 请求发送

### Rebalance 弊端
1. 在 Rebalance 过程中，所有 Consumer 实例都会停止消费，等待 Rebalance 完成，影响 Consumer 端 TPS
2. Rebalance 的设计是所有 Consumer 实例共同参与，全部重新分配所有分区。其实更高效的做法是尽量减少分配方案的变动，这样消费者能够尽量复用之前与 Broker 建立的 TCP 连接
3. Rebalance 很慢

### Coordinator
在 Rebalance 过程中，所有 Consumer 实例共同参与，在协调者组件的帮助下，完成订阅主题分区的分配。其中，协调者负责为 Group 执行 Rebalance 以及提供位移管理和组成员管理等

Consumer 端应用程序在提交位移时，向 Coordinator 所在的 Broker 提交位移。Consumer 应用启动时，向 Coordinator 所在的 Broker 发送各种请求，然后由 Coordinator 负责执行消费者组的注册、成员管理记录等元数据管理操作。所有 Broker 在启动时，都会创建和开启相应的 Coordinator 组件。也就是说，所有 Broker 都有各自的 Coordinator 组件。Kafka 为某个 Consumer Group 确定 Coordinator 所在的 Broker 的算法有以下 2 个步骤：

1. 确定保存该 Group 数据的位移主题分区：`Math.abs(groupId.hashCode() % offsetsTopicPartitionCount)`
2. 找出该分区 Leader 副本所在的 Broker，该 Broker 即为对应的 Coordinator

### 避免 Rebalance
Rebalance 发生的最常见的原因就是 Consumer Group 下的 Consumer 实例数量发生变化。当我们启动一个配置有相同 group.id 值的 Consumer 程序时，实际上就向这个 Group 添加了一个新的 Consumer 实例。此时，Coordinator 会接纳这个新实例，将其加入到组中，并重新分配分区。通常来说，增加 Consumer 实例的操作都是计划内的，可能是出于增加 TPS 或提高伸缩性的需要

我们更关注的是 Group 下实例数减少的情况，即 Consumer 实例被 Coordinator 错误地认为已停止从而被踢出 Group。当 Consumer Group 完成 Rebalance 之后，每个 Consumer 实例都会定期地向 Coordinator 发送心跳请求，表明它还存活着。如果某个 Consumer 实例不能及时地发送这些心跳请求，Coordinator 就会认为该 Consumer 已经死了，从而将其从 Group 中移除，然后开启新一轮 Rebalance

Coordinator 判断 Consumer 的存活状态，受以下几个参数影响：
1. `session.timeout.ms`，默认值是 10 秒，如果 Coordinator 在 10 秒之内没有收到 Group 下某 Consumer 实例的心跳，它就会认为这个 Consumer 实例已经挂了。`session.timout.ms` 决定了 Consumer 存活性的时间间隔
2. `heartbeat.interval.ms`，控制发送心跳请求频率。这个值设置得越小，Consumer 实例发送心跳请求的频率就越高。频繁地发送心跳请求会额外消耗带宽资源，但好处是能够更加快速地知晓当前是否开启 Rebalance。因为，目前 Coordinator 通知各个 Consumer 实例开启 Rebalance 的方法，就是将 REBALANCE_NEEDED 标志封装进心跳请求的响应体中
3. `max.poll.interval.ms`，控制 Consumer 实际消费能力对 Rebalance 的影响。这个参数限定了 Consumer 端应用程序两次调用 poll 方法的最大时间间隔，默认值是 5 分钟，表示 Consumer 如果在 5 分钟之内无法消费完 poll 方法返回的消息，那么 Consumer 会主动发起离开组的请求，Coordinator 也会开启新一轮 Rebalance

避免 Rebalance，主要从以下几个方面考虑：
1. 避免未能及时发送心跳，导致 Consumer 被踢出 Group。仔细地设置 `session.timeout.ms` 和 `heartbeat.interval.ms` 的值。设置 `session.timeout.ms = 6s`，`heartbeat.interval.ms = 2s`。保证 Consumer 实例被踢出之前，能够发送至少 3 轮的心跳请求
2. 避免 Consumer 消费时间过长，将 `max.poll.interval.ms` 设置得大一点，为业务处理逻辑留下充足的时间。这样，Consumer 就不会因为处理这些消息的时间太长而引发 Rebalance 了
3. 如果参数设置合适，却还是出现了 Rebalance，那么可以排查一下 Consumer 端的 GC 表现，比如是否出现了频繁的 Full GC 导致的长时间停顿，从而引发了 Rebalance

### CommitFailedException
Consumer 客户端在提交位移时出现了不可恢复的严重异常，有以下两种原因导致该异常发生：

#### 原因一
当消息处理的总时间超过预设的 `max.poll.interval.ms` 参数值时，Consumer 端会抛出 `CommitFailedException` 异常。要防止这种场景下抛出异常，有以下 4 种方法：
1. 缩短单条消息处理的时间
2. 增加 Consumer 端允许下游系统消费一批消息的最大时长，调整 `max.poll.interval.ms`
3. 减少下游系统一次性消费的消息总数，调整 `max.poll.records`
4. 下游系统使用多线程来加速消费

#### 原因二
消费者组和独立消费者在使用之前都要指定 group.id，如果同时出现了相同 `group.id` 的消费者组程序和独立消费者程序，那么当独立消费者程序手动提交位移时，Kafka 就会立即抛出 `CommitFailedException` 异常

增加期望的时间间隔 max.poll.interval.ms 参数值。减少 poll 方法一次性返回的消息数量，即减少 max.poll.records 参数值

### ConsumerRebalanceListener
调用 `subscribe()` 方法时提及再均衡监听器 `ConsumerRebalanceListener`，该接口包含以下两个方法：
```java
void onPartitionsRevoked(Collection<TopicPartition> partitions);
```
这个方法会在再均衡开始之前和消费者停止读取消息之后被调用。可以通过这个回调方法来处理消费位移的提交，以此来避免一些不必要的重复消费现象的发生。参数 partitions 表示再均衡前所分配到的分区

```java
void onPartitionsAssigned(Collection<TopicPartition> partitions);
```
这个方法会在重新分配分区之后和消费者开始读取消费之前被调用。参数 partitions 表示再均衡后所分配到的分区

```java
Map<TopicPartition, OffsetAndMetadata> currentOffsets = new HashMap<>();
consumer.subscribe(Arrays.asList(topic), new ConsumerRebalanceListener() {
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        consumer.commitSync(currentOffsets);
        currentOffsets.clear();
    }

    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {}
});

try {
    while (isRunning.get()) {
        ConsumerRecords<String, String> records =
            consumer.poll(Duration.ofMillis(100));
        for (ConsumerRecord<String, String> record : records) {
            //process the record.
            currentOffsets.put(
                    new TopicPartition(record.topic(), record.partition()),
                    new OffsetAndMetadata(record.offset() + 1));
        }
        consumer.commitAsync(currentOffsets, null);
    }
} finally {
    consumer.close();
}
```
再均衡监听器还可以配合外部存储使用：在 `onPartitionsRevoked` 方法中将消费位移保存在数据库中，然后再 `onPartitionsAssigned` 方法中读取位移并通过 `seek` 确定再均衡之后的分区位移