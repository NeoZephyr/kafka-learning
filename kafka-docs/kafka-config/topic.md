与主题相关的所有配置参数在 broker 层面都有对应参数。如果没有修改过主题的任何配置参数，那么就会使用 broker 端的对应参数作为其默认值


## max.message.bytes
消息的最大字节数，默认值为 1000012。对应的 broker 端参数为 message.max.bytes

此外，还有两个参数，限制消息大小：
replica.fetch.max.bytes -> 复制
fetch.message.max.bytes -> 消费


## retention.bytes
分区中所能保留的消息总量，默认值为 -1，即没有限制。对应的 broker 端参数为 log.retention.bytes


## retention.ms
使用 delete 的日志清理策略时消息能够保留多长时间，默认值为 604800000，即 7 天。Topic 消息被保存的时长，默认是 7 天。如果设置为 -1，则表示没有限制。对应的 broker 端参数为 log.retention.ms


## flush.messages
需要收集多少消息才会将它们强制刷新到磁盘，默认值为 Long.MAX_VALUE，即让操作系统来决定。建议不要修改此参数的默认值。对应的 broker 端参数为 log.flush.interval.messages


## flush.ms
需要等待多久才会将消息强制刷新到磁盘，默认值为 Long.MAX_VALUE，即让操作系统来决定。建议不要修改此参数的默认值。对应的 broker 端参数为 log.flush.interval.ms


## follower.replication.throttled.replicas
配置被限制速率的主题所对应的 follower 副本列表。对应的 broker 端参数为 follower.replication.throttled.replicas


## leader.replication.throttled.replicas
配置被限制速率的主题所对应的 leader 副本列表。对应的 broker 端参数为 leader.replication.throttled.replicas


## min.insync.replicas
分区 ISR 集合中至少要有多少个副本，默认值为 1。对应的 broker 端参数为 min.insync.replicas


## segment.bytes
日志分段的最大值，默认值为 1073741824，即 1GB。对应的 broker 端参数为 log.segment.bytes


## segment.index.bytes
日志分段索引的最大值，默认值为 10485760，即 10MB。对应的 broker 端参数为 log.index.size.max.bytes


## segment.jitter.ms
滚动日志分段时，在 segment.ms 的基础之上增加的随机数，默认为 0。对应的 broker 端参数为 log.roll.jitter.ms


## segment.ms
最长多久滚动一次日志分段，默认值为 604800000，即 7 天。对应的 broker 端参数为 log.roll.ms


## unclean.leader.election.enable
是否可以从非 ISR 集合中选举 leader 副本，默认值为 false，如果设置为 true，则可能造成数据丢失。对应的 broker 端参数为 unclean.leader.election.enable


## cleanup.policy
日志压缩策略，默认值为 delete，还可以配置为 compact。对应的 broker 端参数为 log.cleanup.policy


## compression.type
消息的压缩类型，默认值为 producer，表示保留生产者中所使用的原始压缩类型。还可以配置为 uncompressed、snappy、lz4、gzip。对应的 broker 端参数为 compression.type


## delete.retention.ms
表示删除的数据能够保留的时间。默认值为 86400000，即 1 天。对应的 broker 端参数为 log.cleaner.delete.retention.ms


## file.delete.delay.ms
清理文件之前可以等待的时间，默认值为 60000，即 1 分钟。对应的 broker 端参数为 log.segment.delete.delay.ms


## index.interval.bytes
用来控制添加索引项的频率。每超过这个参数所设置的消息字节数时就可以添加一个新的索引项，默认值为 4096。对应的 broker 端参数为 log.index.interval.bytes


## message.format.version
消息格式的版本，默认值为 2.0-IV1。对应的 broker 端参数为 log.message.format.version


## message.timestamp.difference.max.ms
消息中自带的时间戳与 broker 收到消息时的时间戳之间最大的差值，默认值为 Long.MAX_VALUE。此参数只有在 meesage. timestamp.type 参数设置为 CreateTime 时才有效。对应的 broker 端参数为 log.message.timestamp.difference.max.ms

## message.timestamp.type
消息的时间戳类型，默认值为 CreateTime，还可以设置为 LogAppendTime。对应的 broker 端参数为 log.message.timestamp.type


## min.cleanable.dirty.ratio
日志清理时的最小污浊率，默认值为 0.5。对应的 broker 端参数为 log.cleaner.min.cleanable.ratio


## min.compaction.lag.ms
日志再被清理前的最小保留时间，默认值为 0。对应的 broker 端参数为 log.cleaner.min.compaction.lag.ms


## preallocate
在创建日志分段的时候是否要预分配空间，默认值为 false。对应的 broker 端参数为 log.preallocate

