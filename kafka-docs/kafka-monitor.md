## 监控数据的来源
### JMX

在使用 JMX 之前需要确保 Kafka 开启了 JMX 的功能（默认关闭）。Kafka 在启动时需要通过配置 JMX_PORT 来设置 JMX 的端口号并以此来开启 JMX 的功能
```sh
JMX_PORT=9999

nohup kafka-server-start.sh server.properties &
```
开启 JMX 之后会在 ZooKeeper 的 /brokers/ids/<brokerId> 节点中有对应的呈现

