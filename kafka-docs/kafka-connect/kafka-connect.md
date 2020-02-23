## Kafka Connect
Kafka Connect 为在 Kafka 和外部数据存储系统之间移动数据提供了一种可靠的且可伸缩的实现方式。可以简单快捷地将数据从 Kafka 中导入或导出。数据范围涵盖关系型数据库、日志和度量数据、Hadoop 和数据仓库、NoSQL 数据存储、搜索索引等

Kafka Connect 有两个核心概念：Source 和 Sink。Source 负责导入数据到 Kafka，Sink 负责从 Kafka 导出数据，它们都被称为 Connector

Kafka Connect 中还有两个重要的概念：Task 和 Worker。Connector 可以把一项工作分割成许多 Task，然后把 Task 分发到各个 Worker 进程中去执行（分布式模式下），Task 不保存自己的状态信息，而是交给特定的 Kafka 主题去保存。Connector 和 Task 都是逻辑工作单位，必须安排在进程中执行，而在 Kafka Connect 中，这些进程就是 Worker

### 特性
1. 通用性：规范化其他数据系统与 Kafka 的集成，简化了连接器的开发、部署和管理
2. 支持独立模式（standalone）和分布式模式（distributed）
3. REST 接口：使用 REST API 提交和管理 Connector
4. 自动位移管理：自动管理位移提交，不需要开发人员干预，降低了开发成本
5. 分布式和可扩展性：Kafka Connect 基于现有的组管理协议来实现扩展 Kafka Connect 集群
6. 流式计算/批处理的集成

### 独立模式
在独立模式下所有的操作都是在一个进程中完成的，这种模式非常适合测试或功能验证的场景。由于是单进程，无法充分利用 Kafka 自身所提供的负载均衡和高容错等特性

在执行这个脚本时需要指定两个配置文件：一个是用于 Worker 进程运行的相关配置文件；另一个是指定 Source 连接器或 Sink 连接器的配置文件，可以同时指定多个连接器配置，每个连接器配置文件对应一个连接器，因此要保证连接器名称全局唯一，连接器名称通过 name 参数指定

#### Source
用于 Worker 进程运行的配置文件
```sh
# $KAFKA_HOME/config/connect-standalone.properties

# 配置与 Kafka 集群连接的地址
bootstrap.servers=localhost:9092
key.converter=org.apache.kafka.connect.json.JsonConverter
value.converter=org.apache.kafka.connect.json.JsonConverter

# 指定 JSON 消息中是否可以包含 schema
key.converter.schemas.enable=true
value.converter.schemas.enable=true

# 指定保存偏移量的文件路径
offset.storage.file.filename=/tmp/connect.offsets

# 设定提交偏移量的频率
offset.flush.interval.ms=10000
```

Source 连接器的配置文件
```sh
# $KAFKA_HOME/config/connect-file-source.properties

# 连接器的名称
name=local-file-source

# 连接器类的全限定名称
connector.class=FileStreamSource

# 指定 Task 的数量
tasks.max=1

# 连接器数据源文件路径
# 在启动连接器前需要先创建
file=/opt/kafka_2.11-2.0.0/source.txt

# 设置连接器把数据导入的主题
# 如果该主题不存在，则连接器会自动创建
topic=topic-connect
```

启动 Source 连接器
```sh
connect-standalone.sh config/connect-standalone.properties config/connect-file-source.properties
```

连接器启动之后，向 source.txt 文件中输入：
```sh
echo "hello kafka connect">> source.txt
echo "hello kafka streams">> source.txt
```

观察主题 topic-connect 中是否包含这两条消息。对于这个示例，我们既可以使用 kafka-console-consumer.sh 脚本，也可以使用 kafka-dump-log.sh 脚本来查看内容。这里再来回顾一下 kafka-dump-log.sh 脚本的用法：

```sh
# 查看主题 topic-connect 中的消息格式为 JSON 字符串并且带有对应的 schema 信息
kafka-dump-log.sh --files /tmp/kafka-logs/topic-connect-0/00000000000000000000.log --print-data-log
```

#### Sink
Sink 连接器：将主题 topic-connect 中的内容通过 Sink 连接器写入文件 sink.txt

```sh
# config/connect-standalone.properties
bootstrap.servers=localhost:9092
key.converter=org.apache.kafka.connect.storage.StringConverter
value.converter=org.apache.kafka.connect.storage.StringConverter
key.converter.schemas.enable=true
value.converter.schemas.enable=true
offset.storage.file.filename=/tmp/connect.offsets
offset.flush.interval.ms=10000
```

```sh
# $KAFKA_HOME/config/connect-file-sink.properties
name=local-file-sink
connector.class=FileStreamSink
tasks.max=1
file=/opt/kafka_2.11-2.0.0/sink.txt
topics=topic-connect
```

启动 Sink 连接器
```sh
connect-standalone.sh config/connect-standalone.properties config/connect-file-sink.properties
```

向主题 topic-connect 中发送消息
```sh
kafka-console-producer.sh --broker-list localhost:9092 --topic topic-connect
```
可以在 sink.txt 文件中看到这条消息

### REST API
查看 Kafka 集群版本信息：GET /
查看当前活跃的连接器列表，显示连接器的名字：GET /connectors
根据指定配置，创建一个新的连接器：POST /connectors
查看指定连接器的信息：GET /connectors/{name}
查看指定连接器的配置信息：GET /connectors/{name}/config
修改指定连接器的配置信息：PUT /connectors/{name/config
查看指定连接器的状态：GET /connectors/{name}/statue
重启指定的连接器：POST /connectors/{name}/restart
暂停指定的连接器：PUT /connectors/{name}/pause
查看指定连接器正在运行的 Task：GET /connectors/{name}/tasks
修改 Task 的配置：POST /connectors/{name}/tasks
查看指定连接器中指定 Task 的状态：GET /connectors/{name}/tasks/{taskId}/status
重启指定连接器中指定的 Task：POST /connectors/{name}/tasks/{tasked}/restart 
删除指定的连接器：DELETE /connectors/{name}

### 分布式模式
分布式模式天然地结合了 Kafka 提供的负载均衡和故障转移功能，能够自动在多个节点机器上平衡负载。不过，以分布式模式启动的连接器并不支持在启动时通过加载连接器配置文件来创建一个连接器，只能通过访问 REST API 来创建连接器

修改 Worker 进程的相关配置文件
```sh
# $KAFKA_HOME/config/connect-distributed.properties

bootstrap.servers=localhost1:9092,localhost2:9092,localhost3:9092
group.id=connect-cluster
key.converter=org.apache.kafka.connect.json.JsonConverter
value.converter=org.apache.kafka.connect.json.JsonConverter
```

启动分布式模式
```sh
bin/connect-distributed.sh config/connect-distributed.properties 
```

创建 Source 连接器，连接器的相关配置如下
```
{
    "name":"local-file-distribute-source",
    "config":{
        "topic":"topic-distribute-source",
        "connector.class":"FileStreamSource",
        "key.converter":"org.apache.kafka.connect.storage.StringConverter",
        "value.converter":"org.apache.kafka.connect.storage.StringConverter",
        "converter.internal.key.converter":
"org.apache.kafka.connect.storage.StringConverter",
        "converter.internal.value.converter":
"org.apache.kafka.connect.storage.StringConverter",
        "file":"/opt/kafka_2.11-2.0.0/distribute-source.txt"
    }
}
```

这个连接器从 distribute-source.txt 文件中读取内容进而传输到主题 topic-distribute-source 中，在创建连接器前确保 distribute-source.txt 文件和主题 topic-distribute-source 都已创建完毕。接下来调用 POST /connectors 接口来创建指定的连接器，示例如下：

```sh
curl -i -X POST -H "Content-Type:application/ json" -H "Accept:application/json" -d '{"name":"local-file-distribute-source", "config":{"topic":"topic-distribute-source","connector.class":"FileStreamSource","key.converter":"org.apache.kafka.connect.storage.StringConverter","value.converter":"org.apache.kafka.connect.storage.StringConverter","converter.internal.key.converter":"org.apache.kafka.connect.storage.StringConverter","converter.internal.value.converter":"org.apache.kafka.connect.storage.StringConverter","file":"/opt/kafka_2.11-2.0.0/distribute-source.txt"}}' http://localhost:8083/connectors
```

向 distribute-source.txt 文件中写入内容，然后订阅消费主题 topic-distribute-source 中的消息来验证是否成功。在使用完毕之后，可以调用 DELETE /connectors/{name} 接口来删除对应的连接器

```sh
curl -i -X DELETE http://localhost:8083/connectors/local-file-distribute-source
```
```sh
curl -i http://localhost:8083/connectors
```
