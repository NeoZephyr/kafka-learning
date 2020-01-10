## 位移
老版本的 Consumer Group 把位移保存在 ZooKeeper 中，不过 ZooKeeper 这类元框架其实并不适合进行频繁的写更新，而 Consumer Group 的位移更新却是一个非常频繁的操作。这种大吞吐量的写操作会极大地拖慢 ZooKeeper 集群的性能，因此在新版本的 Consumer Group 中，Kafka 社区重新设计了 Consumer Group 的位移管理方式，采用了将位移保存在 Kafka 内部主题（__consumer_offsets）的方法

### 位移类型
假设消费者已经消费了分区 x 位置的消息，那么消费者的消费位移为 x，即为 lastConsumedOffset。但是，当前消费者需要提交的消费位移并不是 x，而是 x+1，表示下一条需要拉取的消息的位置

```java
TopicPartition tp = new TopicPartition(topic, 0);
consumer.assign(Arrays.asList(tp));
long lastConsumedOffset = -1;
while (true) {
    ConsumerRecords<String, String> records = consumer.poll(1000);
    if (records.isEmpty()) {
        break;
    }
    List<ConsumerRecord<String, String>> partitionRecords
            = records.records(tp);
    lastConsumedOffset = partitionRecords
            .get(partitionRecords.size() - 1).offset();
    consumer.commitSync();
}

OffsetAndMetadata offsetAndMetadata = consumer.committed(tp);
long posititon = consumer.position(tp);

// x
System.out.println("comsumed offset is " + lastConsumedOffset);

// x + 1
System.out.println("commited offset is " + offsetAndMetadata.offset());

// x + 1
System.out.println("the offset of the next record is " + posititon);
```

### 位移主题
新版本 Consumer 的位移管理机制很简单，就是将 Consumer 的位移数据作为一条条普通的 Kafka 消息，提交到位移主题中。因此，位移主题的主要作用是保存 Kafka 消费者的位移信息

位移主题的消息格式有以下 3 种：
1. 位移主题的 Key 中应该保存消费者组 id，主题名，分区号
2. 保存 Consumer Group 信息的消息，主要是用于注册 Consumer Group
3. 用于删除 Group 过期位移甚至是删除 Group 的消息。消息体是 null，即空消息体

当 Kafka 集群中的第一个 Consumer 程序启动时，Kafka 会自动创建位移主题。位移主题的分区数受 Broker 端参数 `offsets.topic.num.partitions` 控制，默认值是 50。位移主题的副本数受 Broker 端另一个参数 `offsets.topic.replication.factor` 控制，默认值是 3

### 位移提交
#### 自动提交位移
Consumer 端有个参数 `enable.auto.commit` 设置为 true，则 Consumer 在后台默默地为你定期提交位移，提交间隔由一个专属的参数 `auto.commit.interval.ms` 来控制，该参数默认值是 5 秒，表明 Kafka 每 5 秒会为你自动提交一次位移。Kafka 会保证在开始调用 `poll()` 方法时，提交上次 `poll()` 返回的所有消息，因此能保证不出现消费丢失的情况，但可能会出现重复消费

自动位移提交的动作是在 `poll()` 方法的逻辑里完成的，在每次真正向服务端发起拉取请求之前会检查是否可以进行位移提交，如果可以，那么就会提交上一次轮询的位移

如果选择自动提交位移，那么只要 Consumer 一直启动着，它就会无限期地向位移主题写入消息。即使该主题没有任何新消息产生，位移主题中仍会不停地写入位移消息。显然 Kafka 只需要保留这类消息中的最新一条就可以了，之前的消息都是可以删除的。这就要求 Kafka 必须要有针对位移主题消息特点的消息删除策略，否则这种消息会越来越多，最终撑爆整个磁盘

Kafka 通过 Compaction 删除位移主题中的过期消息，避免该主题无限期膨胀。对于同一个 Key 的两条消息 M1 和 M2，如果 M1 的发送时间早于 M2，那么 M1 就是过期消息。Compact 的过程就是扫描日志的所有消息，剔除那些过期的消息，然后把剩下的消息整理在一起

Kafka 提供了专门的后台线程定期地巡检待 Compact 的主题，看看是否存在满足条件的可删除数据。这个后台线程叫 Log Cleaner。很多实际生产环境中出现过位移主题无限膨胀占用过多磁盘空间的问题，可能跟 Log Cleaner 线程的状态有关系

#### 手动提交位移
设置 `enable.auto.commit` 为 false，需要手动提交位移

同步提交方法：`commitSync()`，该方法会提交 `poll()` 返回的最新位移。同步提交会一直等待，直到位移被成功提交才会返回。如果提交过程中出现异常，该方法会将异常信息抛出
```java
while (true) {
    ConsumerRecords<String, String> records =
        consumer.poll(Duration.ofSeconds(1));
    process(records); // 处理消息
    try {
        consumer.commitSync();
    } catch (CommitFailedException e) {
        handle(e); // 处理提交失败异常
    }
}
```

异步提交方法：`commitAsync()`，调用该方法会立即返回，不会阻塞，因此不会影响 Consumer 应用的 TPS。由于它是异步的，Kafka 提供了回调函数，可以在提交之后记录日志或处理异常等
```java
while (true) {
    ConsumerRecords<String, String> records = 
        consumer.poll(Duration.ofSeconds(1));
    process(records);
    consumer.commitAsync((offsets, exception) -> {
        if (exception != null)
            handle(exception);
    });
}
```

如果使用 `commitAsync` 提交方法，出现问题时不会自动重试。因为它是异步操作，倘若提交失败后自动重试，那么它重试时提交的位移值可能早已经过期或不是最新值了。因此，异步提交的重试其实没有意义，所以 commitAsync 是不会重试的

对于常规性、阶段性的手动提交，调用 `commitAsync()` 避免程序阻塞，而在 Consumer 要关闭前，我们调用 `commitSync()` 方法执行同步阻塞式的位移提交，以确保 Consumer 关闭前能够保存正确的位移数据
```java
try {
    while(true) {
        ConsumerRecords<String, String> records = 
                    consumer.poll(Duration.ofSeconds(1));
        process(records); // 处理消息
        commitAysnc(); // 使用异步提交规避阻塞
    }
} catch(Exception e) {
    handle(e); // 处理异常
} finally {
    try {
        consumer.commitSync(); // 最后一次提交使用同步阻塞式提交
    } finally {
        consumer.close();
    }
}
```

如果 poll 方法返回很多条消息，我们可以每处理完一部分消息就提交一次位移，这样能够避免大批量的消息重新消费。手动提交提供了这样的方法：`commitSync(Map)` 和 `commitAsync(Map)`。它们的参数是一个 Map 对象，键就是 TopicPartition，即消费的分区，而值是一个 OffsetAndMetadata 对象，保存的主要是位移数据

分批次提交位移
```java
private Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
int count = 0;

while (true) {
    ConsumerRecords<String, String> records =
        consumer.poll(Duration.ofSeconds(1));
    for (ConsumerRecord<String, String> record: records) {
        process(record);  // 处理消息
        offsets.put(new TopicPartition(record.topic(), record.partition()),
                   new OffsetAndMetadata(record.offset() + 1)；
        if（count % 100 == 0）
            consumer.commitAsync(offsets, null); // 回调处理逻辑是null
        count++;
    }
}
```

按分区粒度提交位移
```java
try {
    while (isRunning.get()) {
        ConsumerRecords<String, String> records = consumer.poll(1000);
        for (TopicPartition partition : records.partitions()) {
            List<ConsumerRecord<String, String>> partitionRecords =
                    records.records(partition);
            for (ConsumerRecord<String, String> record : partitionRecords) {
                process(record);
            }
            long lastConsumedOffset = partitionRecords
                    .get(partitionRecords.size() - 1).offset();
            consumer.commitSync(Collections.singletonMap(partition,
                    new OffsetAndMetadata(lastConsumedOffset + 1)));
        }
    }
} finally {
    consumer.close();
}
```

### 控制位移
#### 自动重置
当消费者查找不到所记录的消费位移、位移越界，会根据消费者客户端参数 `auto.offset.reset` 的配置来决定从何处开始进行消费。该参数的默认值为 latest，表示从分区末尾开始消费消息。如果将该参数配置为 earliest，那么消费者会从起始处开始消费；如果设置为 none，就会抛出 `NoOffsetForPartitionException` 异常

#### 手动重置
有些时候，需要从特定的位移处开始拉取消息，可以通过 KafkaConsumer 中的 `seek()` 方法实现
```java
public void seek(TopicPartition partition, long offset);
```

`seek()` 方法只能设置消费者分配到的分区的消费位置，而分区的分配是在 `poll()` 方法的调用过程中实现的。因此，在执行 `seek()` 方法之前需要先执行一次 `poll()` 方法。如果对未分配到的分区执行 `seek()` 方法，那么会报出 `IllegalStateException` 的异常
```java
KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
consumer.subscribe(Arrays.asList(topic));
Set<TopicPartition> assignment = new HashSet<>();

// 如果不为 0，则说明已经成功分配到了分区
while (assignment.size() == 0) {
    consumer.poll(Duration.ofMillis(100));

    // 获取消费者所分配到的分区信息
    assignment = consumer.assignment();
}

// 获取指定分区的末尾的消息位置
Map<TopicPartition, Long> offsets = consumer.endOffsets(assignment);
Map<TopicPartition, Long> offsets = consumer.beginningOffsets(assignment);

for (TopicPartition tp : assignment) {
    consumer.seek(tp, 10);
    // consumer.seek(tp, offsets.get(tp));
}
while (true) {
    ConsumerRecords<String, String> records =
            consumer.poll(Duration.ofMillis(1000));
    //consume the record.
}
```

有时候我们并不知道特定的消费位置，只是知道一个相关的时间点，此时可以使用 `offsetsForTimes()` 方法，查询具体时间点对应的分区位置，该方法会返回时间戳大于等于待查询时间的第一条消息对应的位置和时间戳
```java
Map<TopicPartition, Long> timestampToSearch = new HashMap<>();

for (TopicPartition tp : assignment) {
    timestampToSearch.put(tp, System.currentTimeMillis() - 8 * 3600 * 1000);
}

Map<TopicPartition, OffsetAndTimestamp> offsets =
    consumer.offsetsForTimes(timestampToSearch);

for (TopicPartition tp : assignment) {
    OffsetAndTimestamp offsetAndTimestamp = offsets.get(tp);
    if (offsetAndTimestamp != null) {
        consumer.seek(tp, offsetAndTimestamp.offset());
    }
}
```