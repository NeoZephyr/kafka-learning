## 重要参数
### fetch.min.bytes
配置 Consumer 在一次拉取请求（调用 poll() 方法）中能从 Kafka 中拉取的最小数据量，默认值为 1B。Kafka 在收到 Consumer 的拉取请求时，如果返回给 Consumer 的数据量小于这个参数所配置的值，那么它就需要进行等待，直到数据量满足这个参数的配置大小。可以适当调大这个参数的值以提高一定的吞吐量，不过也会造成额外的延迟（latency），对于延迟敏感的应用可能就不可取了

### fetch.max.bytes
配置 Consumer 在一次拉取请求中从 Kafka 中拉取的最大数据量，默认值为 52428800B，也就是 50MB

该参数设定的不是绝对的最大值，如果在第一个非空分区中拉取的第一条消息大于该值，那么该消息将仍然返回，以确保消费者继续工作

与此相关的，Kafka 中所能接收的最大消息的大小通过服务端参数 message.max.bytes（对应于主题端参数 max.message.bytes）来设置

### fetch.max.wait.ms
fetch.max.wait.ms 参数用于指定 Kafka 的等待时间，默认值为 500ms。如果 Kafka 中没有足够多的消息而满足不了 fetch.min.bytes 参数的要求，那么最终会等待 500ms。如果业务应用对延迟敏感，可以适当调小这个参数

### max.partition.fetch.bytes
配置从每个分区里返回给 Consumer 的最大数据量，默认值为 1048576B，即 1MB。这个参数与 fetch.max.bytes 参数相似，只不过前者用来限制一次拉取中每个分区的消息大小，而后者用来限制一次拉取中整体消息的大小。同样，如果这个参数设定的值比消息的大小要小，也不会造成无法消费，Kafka 为了保持消费逻辑的正常运转不会对此做强硬的限制

###  max.poll.records
配置 Consumer 在一次拉取请求中拉取的最大消息数，默认值为 500条。如果消息的大小都比较小，可以适当调大这个参数值来提升一定的消费速度

### connections.max.idle.ms
指定在多久之后关闭闲置的连接，默认值是 540000ms，即 9 分钟

### exclude.internal.topics
Kafka 中有两个内部的主题： __consumer_offsets 和 __transaction_state。exclude.internal.topics 用来指定 Kafka 中的内部主题是否可以向消费者公开，默认值为 true。如果设置为 true，只能使用 subscribe(Collection) 的方式而不能使用 subscribe(Pattern) 的方式来订阅内部主题，设置为 false 则没有这个限制

### receive.buffer.bytes
设置 Socket 接收消息缓冲区（SO_RECBUF）的大小，默认值为 65536B，即 64KB。如果设置为 -1，则使用操作系统的默认值。如果 Consumer 与 Kafka 处于不同的机房，则可以适当调大这个参数值

### send.buffer.bytes
设置 Socket 发送消息缓冲区（SO_SNDBUF）的大小，默认值为 131072B，即 128KB。如果设置为 -1，则使用操作系统的默认值

### request.timeout.ms
配置 Consumer 等待请求响应的最长时间，默认值为 30000ms

### metadata.max.age.ms
配置元数据的过期时间，默认值为 300000ms，即 5 分钟。如果元数据在此参数所限定的时间范围内没有进行更新，则会被强制更新，即使没有任何分区变化或有新的 broker 加入

### reconnect.backoff.ms
配置尝试重新连接指定主机之前的等待时间（也称为退避时间），避免频繁地连接主机，默认值为 50ms。这种机制适用于消费者向 broker 发送的所有请求

### retry.backoff.ms
配置尝试重新发送失败的请求到指定的主题分区之前的等待（退避）时间，避免在某些故障情况下频繁地重复发送，默认值为 100ms

### isolation.level
配置消费者的事务隔离级别。有效值为 read_uncommitted 和 read_committed，表示消费者所消费到的位置，如果设置为 read_committed，那么消费者就会忽略事务未提交的消息，即只能消费到 LSO（LastStableOffset）的位置，默认情况下为 read_uncommitted，即可以消费到 HW（High Watermark）处的位置