## 日志
Kafka 日志对象由多个日志段对象组成，而每个日志段对象会在磁盘上创建一组文件，包括消息日志文件（.log）、位移索引文件（.index）、时间戳索引文件（.timeindex）以及已中止事务的索引文件（.txnindex）。如果没有使用 Kafka 事务，已中止事务的索引文件是不会被创建出来的。索引文件的文件名中的一串数字是该日志段的起始位移值，也就是该日志段中所存的第一条消息的位移值

一个 Kafka 主题有很多分区，每个分区就对应一个 Log 对象，在物理磁盘上则对应于一个子目录，在服务器端，对应于一个 Log 对象。每个子目录下存在多组日志段，也就是多组 .log、.index、.timeindex 文件组合


core/src/main/scala/kafka/log/LogSegment.scala

在 Scala 语言里，同时定义相同名字的 class 和 object 的用法被称为伴生。Class 对象被 称为伴生类，它和 Java 中的类是一样的；而 Object 对象是一个单例对象，用于保存一些静态变量或静态方法。如果用 Java 来做类比的话，我们必须要编写两个类才能实现，也就是 LogSegment 和 LogSegmentUtils

每个日志段都有一个起始位移值，该位移值是此日志段所有消息中最小的位移值，同时，该值却又比前面任何日志段中消息的位移值都大

```scala
class LogSegment private[log] (val log: FileRecords,
                               val lazyOffsetIndex: LazyIndex[OffsetIndex],
                               val lazyTimeIndex: LazyIndex[TimeIndex],
                               val txnIndex: TransactionIndex,
                               val baseOffset: Long,
                               val indexIntervalBytes: Int,
                               val rollJitterMs: Long,
                               val time: Time) extends Logging {}
```
indexIntervalBytes 值就是 Broker 端参数 log.index.interval.bytes 值，它控制了日志段对象新增索引项的频率。默认情况下，日志段至少新写入 4KB 的消息数据才会新增一条索引项

rollJitterMs 是日志段对象新增倒计时的扰动值。因为目前 Broker 端日志段新增倒计时是全局设置，这就是说，在未来的某个时刻可能同时创建多个日志段对象，这将极大地增加物理磁盘 I/O 压力。有了 rollJitterMs 值的干扰，每个新增日志段在创建时会彼此岔开一小段时间，可以缓解物理磁盘的 I/O 负载瓶颈


写入消息
调用 log.sizeInBytes 方法判断该日志段是否为空，如果是空的话， Kafka 需要记录要写入消息集合的最大时间戳，并将其作为后面新增日志段倒计时的依据

代码调用 ensureOffsetInRange 方法确保输入参数最大位移值是合法的。标准就是看它与日志段起始位移的差值是否在整数范围内，即 largestOffset - baseOffset 的值是不是介于 [0，Int.MAXVALUE] 之间。在极个别的情况下，这个差值可能会越界，这时，append 方法就会抛出异常，阻止后续的消息写入。一旦碰到这个问题，需要升级 Kafka 版本，因为这是由已知的 Bug 导致的

调用 FileRecords 的 append 方法执行真正的写入。将内存中的消息对象写入到操作系统的页缓存

更新日志段的最大时间戳以及最大时间戳所属消息的位移值属性。每个日志段都要保存当前最大时间戳信息和所属消息的位移信息。最大时间戳对应的消息的位移值则用于时间戳索引项（时间戳索引项保存时间戳与消息位移的对应关系）

更新索引项和写入的字节数。日志段每写入 4KB 数据就要写入一个索引项。当已写入字节数超过了 4KB 之后，append 方法会调用索引对象的 append 方法新增索引项，同时清空已写入字节数，以备下次重新累积计算


读取消息
minOneMessage 为 true 表示即使出现消息体字节数超过了 maxSize 的情形，read 方法依然能返回至少一条消息

调用 translateOffset 方法定位要读取的起始文件位置。输入参数 startOffset 仅仅是位移值，Kafka 需要根据索引信息找到对应的物理文件位置才能开始读取消息

日志段代码需要根据读取起始位置信息以及 maxSize 和 maxPosition 参 数共同计算要读取的总字节数

调用 FileRecords 的 slice 方法，从指定位置读取指定大小的消息集合


恢复日志段
Broker 在启动时会从磁盘上加载所有日志段信息到内存中，并创建相应的 LogSegment 对象实例

依次调用索引对象的 reset 方法清空所有的索引文件

遍历日志段中的所有消息集合或消息批次。对于读取到的每个消息集合，必须要确保是合法的：
1. 该集合中的消息必须要符合 Kafka 定义的二进制格式
2. 该集合中最后一条消息的位移值不能越界

更新遍历过程中观测到的最大时间戳以及所属消息的位移值。同样，这两个数据用于后续构建索引项。再之后就是不断累加当前已读取的消息字节数，并根据该值有条件地写入索引项。最后是更新事务型 Producer 的状态以及 Leader Epoch 缓存

Kafka 会将日志段当前总字节数和刚刚累加的已读取字节数进行比较，如果发现前者比后者大，说明日志段写入了一些非法消息，需要执行截断操作，将日志段大小调整回合法的数值。同时， Kafka 还必须相应地调整索引文件的大小。把这些都做完之后，日志段恢复的操作也就宣告结束了

