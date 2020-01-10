### 拦截器
消费者拦截器包含以下方法：
```java
public ConsumerRecords<K, V> onConsume(ConsumerRecords<K, V> records);
```
消费者会在 `poll()` 方法返回之前调用拦截器的 `onConsume()` 方法来对消息进行相应的定制化操作，比如修改返回的消息内容、按照某种规则过滤消息。如果该方法中抛出异常，那么会被捕获并记录到日志中，但是异常不会再向上传递

```java
public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets);
```
消费者会在提交完消费位移之后调用拦截器的 `onCommit()` 方法，可以使用这个方法来记录跟踪所提交的位移信息

在某些业务场景中会对消息设置一个有效期的属性，如果某条消息在既定的时间窗口内无法到达，那么就会被视为无效，它也就不需要再被继续处理了
```java
public class ConsumerInterceptorTTL implements 
    ConsumerInterceptor<String, String> {

    private static final long EXPIRE_INTERVAL = 10 * 1000;

    public ConsumerRecords<String, String> onConsume(
        ConsumerRecords<String, String> records) {
        long now = System.currentTimeMillis();
        Map<TopicPartition, List<ConsumerRecord<String, String>>> newRecords 
            = new HashMap<>();
        for (TopicPartition tp : records.partitions()) {
            List<ConsumerRecord<String, String>> tpRecords = 
            records.records(tp);
            List<ConsumerRecord<String, String>> newTpRecords = new ArrayList<>();
            for (ConsumerRecord<String, String> record : tpRecords) {
                if (now - record.timestamp() < EXPIRE_INTERVAL) {
                    newTpRecords.add(record);
                }
            }
            if (!newTpRecords.isEmpty()) {
                newRecords.put(tp, newTpRecords);
            }
        }
        return new ConsumerRecords<>(newRecords);
    }

    public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {
        offsets.forEach((tp, offset) -> 
                System.out.println(tp + ":" + offset.offset()));
    }

    public void close() {}

    public void configure(Map<String, ?> configs) {}
}
```

```java
props.put(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG,
    ConsumerInterceptorTTL.class.getName());
```

不过使用这种功能时需要注意的是：在一次消息拉取的批次中，可能含有最大偏移量的消息会被消费者拦截器过滤

在消费者中也有拦截链的概念，和生产者的拦截链一样，也是按照 `interceptor.classes` 参数配置的拦截器的顺序来一一执行的（配置的时候，各个拦截器之间使用逗号隔开）。同样也要提防副作用的发生。如果在拦截链中某个拦截器执行失败，那么下一个拦截器会接着从上一个执行成功的拦截器继续执行


## 反序列化
```java
public class CompanyDeserializer implements Deserializer<Company> {
    public void configure(Map<String, ?> configs, boolean isKey) {}

    public Company deserialize(String topic, byte[] data) {
        if (data == null) {
            return null;
        }
        if (data.length < 8) {
            throw new SerializationException("Size of data received " +
                    "by DemoDeserializer is shorter than expected!");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int nameLen, addressLen;
        String name, address;

        nameLen = buffer.getInt();
        byte[] nameBytes = new byte[nameLen];
        buffer.get(nameBytes);
        addressLen = buffer.getInt();
        byte[] addressBytes = new byte[addressLen];
        buffer.get(addressBytes);

        try {
            name = new String(nameBytes, "UTF-8");
            address = new String(addressBytes, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new SerializationException("Error occur when deserializing!");
        }

        return new Company(name,address);
    }

    public void close() {}
}
```


## 消费维度
### 分区维度
```java
ConsumerRecords<String, String> records =
    consumer.poll(Duration.ofMillis(1000));
for (TopicPartition tp : records.partitions()) {
    for (ConsumerRecord<String, String> record : records.records(tp)) {
        System.out.println(record.partition() + " : " + record.value());
    }
}
```

### 主题维度
```java
ConsumerRecords<String, String> records =
    consumer.poll(Duration.ofMillis(1000));
for (String topic : topicList) {
    for (ConsumerRecord<String, String> record : 
            records.records(topic)) {
        System.out.println(record.topic() + " : " + record.value());
    }
}
```


## 多线程消费
KafkaProducer 是线程安全的，而 KafkaConsumer 却是非线程安全的。KafkaConsumer 中的 acquire() 方法用来检测当前是否只有一个线程在操作，若有其他线程正在操作则会抛出 ConcurrentModifcationException 异常

KafkaConsumer 中的每个公用方法在执行所要执行的动作之前都会调用这个 acquire() 方法，只有 wakeup() 方法例外

```java
private final AtomicLong currentThread = new AtomicLong(NO_CURRENT_THREAD);

private void acquire() {
    long threadId = Thread.currentThread().getId();
    if (threadId != currentThread.get() &&
            !currentThread.compareAndSet(NO_CURRENT_THREAD, threadId))
        throw new ConcurrentModificationException
                ("KafkaConsumer is not safe for multi-threaded access");
    refcount.incrementAndGet();
}
```

acquire() 方法和 release() 方法成对出现
```java
private void release() {
    if (refcount.decrementAndGet() == 0)
        currentThread.set(NO_CURRENT_THREAD);
}
```

多线程的实现方式有多种，第一种也是最常见的方式：线程封闭，即为每个线程实例化一个 KafkaConsumer 对象。这种实现方式的并发度受限于分区的实际个数，当消费线程的个数大于分区数时，就有部分消费线程一直处于空闲的状态
```java
public class MultiConsumerThreadDemo {
    public static final String brokerList = "localhost:9092";
    public static final String topic = "topic-demo";
    public static final String groupId = "group.demo";

    public static Properties initConfig() {
        Properties props = new Properties();
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokerList);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        return props;
    }

    public static void main(String[] args) {
        Properties props = initConfig();
        int consumerThreadNum = 4;
        for(int i = 0; i < consumerThreadNum; i++) {
            new KafkaConsumerThread(props,topic).start();
        }
    }

    public static class KafkaConsumerThread extends Thread {
        private KafkaConsumer<String, String> kafkaConsumer;
        private AtomicBoolean closed = new AtomicBoolean(false);

        public KafkaConsumerThread(Properties props, String topic) {
            this.kafkaConsumer = new KafkaConsumer<>(props);
            this.kafkaConsumer.subscribe(Arrays.asList(topic));
        }

        @Override
        public void run() {
            try {
                while (!closed.get()) {
                    ConsumerRecords<String, String> records =
                            kafkaConsumer.poll(Duration.ofMillis(100));
                    for (ConsumerRecord<String, String> record : records) {
                        //处理消息模块
                    }
                }
            } catch (WakeupException e) {
                if (!closed.get()) {
                    throw e;
                }
            } finally {
                kafkaConsumer.close();
            }
        }

        // Shutdown hook which can be called from a separate thread
        public void shutdown() {
            closed.set(true);
            consumer.wakeup();
        }
    }
}
```

该方案有以下优点：
实现简单，多个线程之间没有任何交互，省去了很多保障线程安全方面的开销；由于每个线程使用专属的 KafkaConsumer 实例来执行消息获取和消息处理逻辑，Kafka 主题中的每个分区都能保证只被一个线程处理，很容易实现分区内的消息消费顺序

该方案的缺点如下：
每个线程都维护自己的 KafkaConsumer 实例会占用更多的系统资源，比如内存、TCP 连接等，如果分区数和消费线程数的值都很大，会造成不小的系统开销；能使用的线程数受限于 Consumer 订阅主题的总分区数；每个线程完整地执行消息获取和消息处理逻辑，一旦消息处理逻辑很重，造成消息处理速度慢，就很容易出现不必要的 Rebalance，从而引发整个消费者组的消费停滞


第二种方式是多个消费线程同时消费同一个分区，这个通过 assign()、seek() 等方法实现，这样可以打破原有的消费线程的个数不能超过分区数的限制，进一步提高了消费的能力。不过这种实现方式对于位移提交和顺序控制的处理就会变得非常复杂，并不推荐


第三种方式是在第一种方式的基础上，将处理消息模块改成多线程的实现方式
```java
public class MultiConsumerThreadDemo {
    public static final String brokerList = "localhost:9092";
    public static final String topic = "topic-demo";
    public static final String groupId = "group.demo";

    public static void main(String[] args) {
        Properties props = initConfig();
        KafkaConsumerThread consumerThread = 
                new KafkaConsumerThread(props, topic,
                Runtime.getRuntime().availableProcessors());
        consumerThread.start();
    }

    public static class KafkaConsumerThread extends Thread {
        private KafkaConsumer<String, String> kafkaConsumer;
        private ExecutorService executorService;
        private int threadNumber;

        public KafkaConsumerThread(Properties props, 
                String topic, int threadNumber) {
            kafkaConsumer = new KafkaConsumer<>(props);
            kafkaConsumer.subscribe(Collections.singletonList(topic));
            this.threadNumber = threadNumber;
            executorService = new ThreadPoolExecutor(
                    threadNumber,
                    threadNumber,
                    0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1000),
                    new ThreadPoolExecutor.CallerRunsPolicy());
        }

        @Override
        public void run() {
            try {
                while (true) {
                    ConsumerRecords<String, String> records =
                            kafkaConsumer.poll(Duration.ofMillis(100));
                    if (!records.isEmpty()) {
                        executorService.submit(new RecordsHandler(records));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                kafkaConsumer.close();
            }
        }
    }

    public static class RecordsHandler extends Thread {
        public final ConsumerRecords<String, String> records;

        public RecordsHandler(ConsumerRecords<String, String> records) {
            this.records = records;
        }

        @Override
        public void run() {
        }
    }
}
```

KafkaConsumerThread 类中 ThreadPoolExecutor 里的最后一个参数设置的是 CallerRunsPolicy()，这样可以防止线程池的总体消费能力跟不上 poll() 拉取的能力，从而导致异常现象的发生


该方案优点如下：
1. 可以横向扩展，通过开启多个 KafkaConsumerThread 实例来进一步提升整体的消费能力
2. 可以减少 TCP 连接对系统资源的消耗

该方案缺点如下：
1. 实现难度大，需要管理两组线程
2. 由于将消息获取和消息处理分开了，无法保证分区内的消费顺序
3. 引入了多组线程，使得整个消息消费链路被拉长，最终导致正确位移提交会变得异常困难，结果就是可能会出现消息的重复消费

针对位移提交问题，有以下可供采用的方案：
每一个处理消息的 RecordHandler 类在处理完消息之后都将对应的消费位移保存到共享变量 offsets 中。需要注意的是，要对 offsets 读写需要加锁处理，防止出现并发问题。并且在写入 offsets 的时候需要注意位移覆盖的问题
```java
// RecordHandler.run()
for (TopicPartition tp : records.partitions()) {
    List<ConsumerRecord<String, String>> tpRecords = records.records(tp);
    long lastConsumedOffset = tpRecords.get(tpRecords.size() - 1).offset();

    synchronized (offsets) {
        if (!offsets.containsKey(tp)) {
            offsets.put(tp, new OffsetAndMetadata(lastConsumedOffset + 1));
        } else {
            long position = offsets.get(tp).offset();
            if (position < lastConsumedOffset + 1) {
                offsets.put(tp, new OffsetAndMetadata(lastConsumedOffset + 1));
            }
        }
    }
}
```

KafkaConsumerThread 在每一次 poll() 方法之后都读取 offsets 中的内容并对其进行位移提交
```java
synchronized (offsets) {
    if (!offsets.isEmpty()) {
        kafkaConsumer.commitSync(offsets);
        offsets.clear();
    }
}
```

其实这种位移提交的方式会有数据丢失的风险。对于同一个分区中的消息，假设一个处理线程 RecordHandler1 正在处理 offset 为 0～99 的消息，而另一个处理线程 RecordHandler2 已经处理完了 offset 为 100～199 的消息并进行了位移提交，此时如果 RecordHandler1 发生异常，则之后的消费只能从 200 开始而无法再次消费 0～99 的消息，从而造成了消息丢失的现象。这里虽然针对位移覆盖做了一定的处理，但还没有解决异常情况下的位移覆盖问题

对此就要引入更加复杂的处理机制，总体结构上是基于滑动窗口实现的。滑动窗口将拉取到的消息暂存起来，多个消费线程可以拉取暂存的消息，这个用于暂存消息的缓存大小即为滑动窗口的大小

滑动窗口中，每一个方格代表一个批次的消息，一个滑动窗口包含若干方格。startOffset 标注的是当前滑动窗口的起始位置，endOffset 标注的是末尾位置。每当 startOffset 指向的方格中的消息被消费完成，就可以提交这部分的位移，与此同时，窗口向前滑动一格，删除原来 startOffset 所指方格中对应的消息，并且拉取新的消息进入窗口。滑动窗口的大小固定，所对应的用来暂存消息的缓存大小也就固定了

方格大小和滑动窗口的大小同时决定了消费线程的并发数：一个方格对应一个消费线程，对于窗口大小固定的情况，方格越小并行度越高；对于方格大小固定的情况，窗口越大并行度越高。不过，若窗口设置得过大，不仅会增大内存的开销，而且在发生异常（比如 Crash）的情况下也会引起大量的重复消费，同时还考虑线程切换的开销，建议根据实际情况设置一个合理的值，不管是对于方格还是窗口而言，过大或过小都不合适

如果一个方格内的消息无法被标记为消费完成，那么就会造成 startOffset 的悬停。为了使窗口能够继续向前滑动，那么就需要设定一个阈值，当 startOffset 悬停一定的时间后就对这部分消息进行本地重试消费，如果重试失败就转入重试队列，如果还不奏效就转入死信队列。真实应用中无法消费的情况极少，一般是由业务代码的处理逻辑引起的，比如消息中的内容格式与业务处理的内容格式不符。这种情况可以通过优化代码逻辑或采取丢弃策略来避免。如果需要消息高度可靠，也可以将无法进行业务逻辑的消息（这类消息可以称为死信）存入磁盘、数据库或 Kafka，然后继续消费下一条消息以保证整体消费进度合理推进，之后可以通过一个额外的处理任务来分析死信进而找出异常的原因


## 消费控制
暂停某些分区在拉取操作时返回数据给客户端
```java
public void pause(Collection<TopicPartition> partitions);
```
恢复某些分区向客户端返回数据
```java
public void resume(Collection<TopicPartition> partitions);
```
返回被暂停的分区集合
```java
public Set<TopicPartition> paused();
```

`wakeup()` 方法是 KafkaConsumer 中唯一可以从其他线程里安全调用的方法，调用该方法后可以退出 `poll()` 的逻辑，并抛出 `WakeupException` 异常（`WakeupException` 异常只是一种跳出循环的方式，不需要处理）
```java
public void wakeup();
```

跳出循环以后一定要显式地执行关闭动作以释放运行过程中占用的各种系统资源：内存、Socket 连接等
```java
public void close();
```


## 消费进度
消费者 Lag 表示消费的滞后程度。如果一个消费者 Lag 值很大，表示消费速度无法跟上生产速度。这有可能导致要消费的数据已经不在操作系统的页缓存中了，而这些数据就会失去享有 Zero Copy 技术的资格，以至于消费者就不得不从磁盘上读取数据，从而进一步拉大消费与生产的差距，导致那些 Lag 原本就很大的消费者会越来越慢，Lag 也会越来越大

监控消费进度
1. kafka-consumer-groups 脚本监控消费者消费进度

查看某个给定消费者的 Lag 值
```sh
kafka-consumer-groups.sh --bootstrap-server <broker> --describe --group <group>
```

2. API 查询当前分区最新消息位移和消费者组最新消费消息位移
```java
public static Map<TopicPartition, Long> lagOf(String groupID, String bootstrapServers) throws TimeoutException {
    Properties props = new Properties();
    props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    try (AdminClient client = AdminClient.create(props)) {

        // 获取给定消费者组的最新消费消息的位移
        ListConsumerGroupOffsetsResult result = client.listConsumerGroupOffsets(groupID);
        try {
            Map<TopicPartition, OffsetAndMetadata> consumedOffsets = result.partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, groupID);
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            try (final KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {

                // 获取订阅分区的最新消息位移
                Map<TopicPartition, Long> endOffsets = consumer.endOffsets(consumedOffsets.keySet());
                return endOffsets.entrySet().stream().collect(
                        Collectors.toMap(
                            entry -> entry.getKey(),
                            entry -> entry.getValue() - consumedOffsets.get(entry.getKey()).offset()
                        ));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 处理中断异常
            return Collections.emptyMap();
        } catch (ExecutionException e) {
            // 处理ExecutionException
            return Collections.emptyMap();
        } catch (TimeoutException e) {
            throw new TimeoutException("Timed out when getting lag for consumer group " + groupID);
        }
    }
}
```

3. Kafka JMX 监控指标
Kafka 消费者提供了一个名为 kafka.consumer:type=consumer-fetch-manager-metrics,client-id="{client-id}" 的 JMX 指标。其中有两组属性：records-lag-max 和 records-lead-min，分别表示此消费者在测试窗口时间内曾经达到的最大的 Lag 值和最小的 Lead 值。其中，Lead 值是指消费者最新消费消息的位移与分区当前第一条消息位移的差值。显然，Lag 越大，Lead 就越小，反之同理