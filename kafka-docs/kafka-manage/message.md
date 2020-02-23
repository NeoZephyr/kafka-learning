 ## 生产与消费
### 生产消息
```sh
kafka-console-producer.sh --broker-list kafka-host:port --topic test-topic --request-required-acks -1 --producer-property compression.type=lz4
```

### 消费消息
```sh
kafka-console-consumer.sh --bootstrap-server kafka-host:port --topic test-topic --group test-group --from-beginning --consumer-property enable.auto.commit=false 
```

### 测试生产者性能
```sh
# 发送 1 千万条消息，每条消息大小是 1KB

# throughput 参数用来进行限流控制，当设定的值小于 0 时不限流
# 当设定的值大于 0 时，如果发送的吞吐量大于该值时就会被阻塞一段时间
kafka-producer-perf-test.sh --topic test-topic --num-records 10000000 --throughput -1 --record-size 1024 --producer-props bootstrap.servers=kafka-host:port acks=-1 linger.ms=2000 compression.type=lz4
```
kafka-producer-perf-test.sh 脚本中有一个参数 print-metrics，指定了这个参数时会在测试完成之后打印指标信息

### 测试消费者性能
```sh
kafka-consumer-perf-test.sh --broker-list kafka-host:port --messages 10000000 --topic test-topic
```


## 删除消息
在执行具体的删除动作之前需要先配置一个 JSON 文件，用来指定所要删除消息的分区及对应的位置
```js
{
    "partitions": [
        {
            "topic": "customer-delete",
            "partition": 0,
            "offset": 10
        },
        {
            "topic": "customer-delete",
            "partition": 1,
            "offset": 11
        },
        {
            "topic": "customer-delete",
            "partition": 2,
            "offset": 12
        }
    ],
    "version": 1
}
```
分别删除主题 customer-delete 下分区 0 中偏移量为 10、分区 1 中偏移量为 11 和分区 2 中偏移量为 12 的消息
```sh
kafka-delete-records.sh --bootstrap-server localhost:9092 --offset-json-file delete.json
```
Kafka 并不会直接删除消息，它在收到 DeleteRecordsRequest 请求之后，将指定分区的 logStartOffset 置为相应的请求值（比如分区 0 的偏移量 10），最终的删除消息的动作还是交由日志删除任务来完成的


## 查看消息文件数据
```sh
# 显示的是消息批次（RecordBatch）或消息集合（MessageSet）的元数据信息
kafka-dump-log.sh --files ../data_dir/kafka_1/test-topic-1/00000000000000000000.log
```
```sh
# 查看每条具体的消息
kafka-dump-log.sh --files ../data_dir/kafka_1/test-topic-1/00000000000000000000.log --deep-iteration
```
```sh
# 查看消息里面的实际数据
kafka-dump-log.sh --files ../data_dir/kafka_1/test-topic-1/00000000000000000000.log --deep-iteration --print-data-log
```
