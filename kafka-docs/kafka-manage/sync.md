## mirror maker
```sh
# consumer.properties 的配置
bootstrap.servers=cluster1:9092
group.id=groupIdMirror
client.id=sourceMirror
partition.assignment.strategy=org.apache.kafka.clients.consumer.RoundRobinAssignor
# producer.properties 的配置 
bootstrap.servers=cluster2:9092
client.id=sinkMirror
```

```sh
kafka-mirror-maker.sh --consumer.config consumer.properties --producer.config producer.properties --whitelist 'topic-mirror'
```

kafka-mirror-maker.sh 可配置的参数如下：
1. abort.on.send.failure：默认为 true
2. consumer.config：用于指定消费者的配置文件，配置文件里有两个必填的参数：boostrap.servers 和 group.id
3. consumer.rebalance.listener：指定再均衡监听器
4. message.handler：指定消息的处理器。这个处理器会在消费者消费到消息之后且在生产者发送消息之前被调用
5. message.handler.args：指定消息处理器的参数，同 message.handler 一起使用
6. num.streams：指定消费线程的数量
7. offset.commit.interval.ms：指定消费位移提交间隔
8. producer.config：指定生产者的配置文件，配置文件里唯一必填的参数是 bootstrap.servers
9. rebalance.listener.args：指定再均衡监听器的参数，同 consumer.rebalance.listener 一起使用
10. whitelist：指定需要复制的源集群中的主题。这个参数可以指定一个正则表达式


如果在配置文件 consumer.properties 中配置的 bootstrap.servers 和在配置文件 producer.properties 中配置的 bootstrap.servers 的 broker 节点地址列表属于同一个集群，启动 Kafka Mirror Maker 之后，只要往主题 topic-mirror 中输入一条数据，那么这条数据会在这个主题内部无限循环复制，直至 Kafka Mirror Maker 关闭