## kafka 流处理
日志先被实时送入到 Kafka Connect 组件，Kafka Connect 组件对日志进行处理后，将其发送到 Kafka 的某个主题上，接着发送到 Kafka Streams 组件，进行实时分析。最后，Kafka Streams 将分析结果发送到 Kafka 的另一个主题上

### Kafka Connect
Kafka Connect 组件负责连通 Kafka 与外部数据系统，连接外部数据源的组件叫连接器。常见的外部数据源包括数据库、KV 存储、搜索系统或文件系统等

使用文件连接器（File Connector）实时读取 Nginx 的 access 日志
```
10.10.13.41 - - [13/Aug/2019:03:46:54 +0800] "GET /v1/open/product_list?user_key=****&user_phone=****&screen_height=1125&screen_width=2436&from_page=1&user_type=2&os_type=ios HTTP/1.0" 200 1321
```
请求参数中的 os_type 字段目前有两个值：ios 和 android。需求是是实时计算当天所有请求中 ios 端和 android 端的请求数

在 Kafka 安装目录下有 config/connect-distributed.properties 文件，修改下列项
```sh
# Kafka 集群
bootstrap.servers=localhost:9092
# 主机名
rest.host.name=localhost
# 端口
rest.port=8083
```

启动 Connect
```sh
bin/connect-distributed.sh config/connect-distributed.properties
```
在浏览器访问 localhost:8083 的 Connect REST 服务，就能看到下面的返回内容：
```
{"version":"2.3.0","commit":"fc1aaa116b661c8a","kafka_cluster_id":"XbADW3mnTUuQZtJKn9P-hA"}
```
查看当前所有 Connector
```sh
curl http://localhost:8083/connectors
```

添加 File Connector。该 Connector 读取指定的文件，并为每一行文本创建一条消息，并发送到特定的 Kafka 主题上。Connector 类是 Kafka 默认提供的 FileStreamSourceConnector。要读取的日志文件在 /var/log 目录下，要发送到 Kafka 的主题名称为 access_log
```
curl -H "Content-Type:application/json" -H "Accept:application/json" http://localhost:8083/connectors -X POST --data '{"name":"file-connector","config":{"connector.class":"org.apache.kafka.connect.file.FileStreamSourceConnector","file":"/var/log/access.log","tasks.max":"1","topic":"access_log"}}'
```

### Kafka Streams
Kafka Streams 是一个用于处理和分析数据的客户端库。它先把存储在 Kafka 中的数据进行处理和分析，然后将最终所得的数据结果回写到 Kafka 或发送到外部系统

Kafka Streams 直接解决了流式处理中的很多问题：
1. 毫秒级延迟的逐个事件处理
2. 有状态的处理，包括连接和聚合类操作
3. 提供了必要的流处理原语，包括高级流处理 DSL 和低级处理器 API。高级流处理 DSL 提供了常用流处理变换操作，低级处理器 API 支持客户端自定义处理器并与状态仓库交互
4. 使用类似 DataFlow 的模型对无序数据进行窗口化处理
5. 具有快速故障切换的分布式处理和容错能力
6. 无停机滚动部署

```xml
<dependency>
    <groupId>org.apache.kafka</groupId>
    <artifactId>kafka-streams</artifactId>
    <version>2.3.0</version>
</dependency>
```
实时读取 access_log 主题，每 2 秒计算一次 ios 端和 android 端请求的总数，并把这些数据写入到 os-check 主题中

1. KStream 是一个由键值对构成的抽象记录流，每个键值对是一个独立单元，即使相同的 key 也不会被覆盖
2. KTable 是一个基于表主键的日志更新流，相同 key 的每条记录只保存最新的一条记录
```java
public class OSCheckStreaming {

    public static void main(String[] args) {
        Properties props = new Properties();

        // 在整个 Kafka 集群中，applicationId 必须唯一
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "os-check-streams");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_WINDOWED_KEY_SERDE_INNER_CLASS, Serdes.StringSerde.class.getName());

        // 创建 KStream 实例，输入主题为 access_log
        final Gson gson = new Gson();
        final StreamsBuilder builder = new StreamsBuilder();
        KStream<String, String> source = builder.stream("access_log");

        source.mapValues(value -> gson.fromJson(value, LogLine.class)).mapValues(LogLine::getPayload)
                .groupBy((key, value) -> value.contains("ios") ? "ios" : "android")
                .windowedBy(TimeWindows.of(Duration.ofSeconds(2L)))
                .count()

                // 将统计结果写入输出主题
                .toStream()
                .to("os-check", Produced.with(WindowedSerdes.timeWindowedSerdeFrom(String.class), Serdes.Long()));

        final Topology topology = builder.build();

        final KafkaStreams streams = new KafkaStreams(topology, props);
        final CountDownLatch latch = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread("streams-shutdown-hook") {
            @Override
            public void run() {
                streams.close();
                latch.countDown();
            }
        });

        try {
            streams.start();
            latch.await();
        } catch (Exception e) {
            System.exit(1);
        }
        System.exit(0);
    }
}

class LogLine {
    private String payload;
    private Object schema;

    public String getPayload() {
        return payload;
    }
}
```

```
bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic os-check --from-beginning --property value.deserializer=org.apache.kafka.common.serialization.LongDeserializer --property print.key=true --property key.deserializer=org.apache.kafka.streams.kstream.TimeWindowedDeserializer --property key.deserializer.default.windowed.key.serde.inner=org.apache.kafka.common.serialization.Serdes\$StringSerde
```


## Kafka Streams 与其他框架
Kafka Streams API 构建的应用就是一个普通的 Java 应用程序，而不是平台

1. Kafka Streams 需要自行打包和部署、管理 Kafka Streams 应用的生命周期。甚至可以将 Kafka Streams 应用嵌入到其他 Java 应用中；其他流处理平台则提供了完整的部署方案，而且通常都存在资源管理器的角色，作业所需的资源完全由框架层的资源管理器来支持
2. Kafka Streams 目前只支持从 Kafka 读数据以及向 Kafka 写数据；其他框架都集成了丰富的上下游数据源连接器
3. Kafka Streams 应用依赖于 Kafka 集群提供的协调功能，来提供高容错性和高伸缩性；其他框架的容错性和扩展性是通过专属的主节点全局来协调控制


## 流处理
### 分类
所有流处理应用本质上都可以分为两类：有状态的应用和无状态的应用。有状态的应用指的是应用中使用了类似于连接、聚合或时间窗口的 API。一旦调用了这些 API，应用就变为有状态的了，这样就需要让 Kafka Streams 保存应用的状态。无状态的应用是指在这类应用中，某条消息的处理结果不会影响或依赖其他消息的处理

### 流表二元性
流在时间维度上聚合之后形成表，表在时间维度上不断更新形成流，这就是所谓的流表二元性

### 示例
```java
public final class WordCountDemo {
    public static void main(final String[] args) {
        final Properties props = new Properties();

        // Kafka Streams 应用的唯一标识
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "wordcount-stream-demo");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        final StreamsBuilder builder = new StreamsBuilder();
        final KStream<String, String> source = builder.stream("wordcount-input-topic");
        final KTable<String, Long> counts = source
            .flatMapValues(value -> Arrays.asList(value.toLowerCase(Locale.getDefault()).split(" ")))
            .groupBy((key, value) -> value)
            .count();

        counts.toStream().to("wordcount-output-topic", Produced.with(Serdes.String(), Serdes.Long()));

        final KafkaStreams streams = new KafkaStreams(builder.build(), props);
        final CountDownLatch latch = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread("wordcount-stream-demo-jvm-hook") {
            @Override
            public void run() {
                streams.close();
                latch.countDown();
            }
        });

        try {
            streams.start();
            latch.await();
        } catch (final Throwable e) {
            System.exit(1);
        }
        System.exit(0)
    }
}
```





