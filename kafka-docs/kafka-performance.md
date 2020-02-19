## 性能调优
### 操作系统层
1. 最好在挂载文件系统时禁掉 atime 更新。atime 的全称是 access time，记录的是文件最后被访问的时间。记录 atime 需要操作系统访问 inode 资源，而禁掉 atime 可以避免 inode 访问时间的写入操作，减少文件系统的写操作数。可以执行 `mount -o noatime` 命令进行设置
2. 文件系统至少选择 ext4 或 XFS。XFS 文件系统具有高性能、高伸缩性等特点，特别适用于生产服务器
3. 建议将 swappiness 设置成一个很小的值，比如 1～10 之间，以防止 Linux 的 OOM Killer 开启随意杀掉进程。可以执行 `sysctl vm.swappiness=N` 来临时设置该值，如果要永久生效，可以修改 /etc/sysctl.conf 文件，增加 vm.swappiness=N，然后重启机器即可
4. 参数 ulimit -n，如果设置太小，会碰到 Too Many File Open 这类的错误
5. 参数 vm.max_map_count，如果设置太小，在一个主题数超多的 Broker 机器上，会碰到 OutOfMemoryError：Map failed 的严重错误，因此建议在生产环境中适当调大此值，比如将其设置为 655360。具体设置方法是修改 /etc/sysctl.conf 文件，增加 vm.max_map_count=655360，保存之后，执行 sysctl -p 命令使它生效
6. 操作系统页缓存大小对 Kafka 而言至关重要。给 Kafka 预留的页缓存越大越好，最小值至少要容纳一个日志段的大小，也就是 Broker 端参数 log.segment.bytes 的值。该参数的默认值是 1GB。预留出一个日志段大小，至少能保证 Kafka 可以将整个日志段全部放入页缓存，这样，消费者程序在消费时能直接命中页缓存，从而避免昂贵的物理磁盘 I/O 操作

### JVM 层
1. 设置堆大小，可以粗略地设置为 6 ～ 8GB。如果想精确调整的话，可以查看 GC log，特别关注 Full GC 之后堆上存活对象的总大小，然后把堆大小设置为该值的 1.5～2 倍。如果你发现 Full GC 没有被执行过，手动运行 `jmap -histo:live <pid>` 就能人为触发 Full GC
2. 建议使用 G1 收集器，比 CMS 收集器的优化难度小。尽力避免 Full GC 的出现，如果你的 Kafka 环境中经常出现 Full GC，可以配置 JVM 参数 -XX:+PrintAdaptiveSizePolicy，来探查一下到底是谁导致的 Full GC。使用 G1 还很容易碰到的一个问题，就是大对象（Large Object），反映在 GC 上的错误，就是 "too many humongous allocations"。所谓的大对象，一般是指至少占用半个区域（Region）大小的对象。如果区域尺寸是 2MB，那么超过 1MB 大小的对象就被视为是大对象。要解决这个问题，除了增加堆大小之外，你还可以适当地增加区域大小，设置方法是增加 JVM 启动参数 -XX:+G1HeapRegionSize=N。默认情况下，如果一个对象超过了 N/2，就会被视为大对象，从而直接被分配在大对象区。如果 Kafka 环境中的消息体都特别大，就很容易出现这种大对象分配的问题

### Broker 端
尽力保持客户端版本和 Broker 端版本一致

### 应用层
1. 不要频繁地创建 Producer 和 Consumer 对象实例
2. 用完及时关闭
3. 合理利用多线程来改善性能


## 调优吞吐量
### broker 端
1. 适当增加 num.replica.fetchers 参数值，但不用超过 cpu 核数
2. 调优 gc 参数以避免经常性出现 full gc

Broker 端参数 num.replica.fetchers 表示的是 Follower 副本用多少个线程来拉取消息，默认使用 1 个线程

### producer 端
1. 适当增加 batch.size 参数值，比如默认的 16kb 到 512kb 或 1mb
2. 适当增加 linger.ms 参数值，比如 10 ~ 100
3. 设置 compression.type=lz4 或者 zstd
4. 设置 acks=0 或 1
5. 设置 retries=0
6. 如果多线程共享同一个 producer 实例，就增加 buffer.memory 参数值

在 Producer 端，增加消息批次的大小以及批次缓存时间，即 batch.size 和 linger.ms，它们的默认值都偏小。由于我们的优化目标是吞吐量，最好不要设置 acks=all 以及开启重试，前者引入的副本同步时间通常都是吞吐量的瓶颈，而后者在执行过程中也会拉低 Producer 应用的吞吐量。如果在多个线程中共享一个 Producer 实例，就可能会碰到缓冲区不够用的情形。倘若频繁地遭遇 TimeoutException：Failed to allocate memory within the configured max blocking time 这样的异常，那么就必须显式地增加 buffer.memory 参数值，确保缓冲区总是有空间可以申请的

### consumer 端
1. 采用多 consumer 进程或者线程同时消费数据
2. 增加 fetch.min.bytes 参数值，比如设置成 1kb 或者更大

可以利用多线程方案增加整体吞吐量，也可以增加 fetch.min.bytes 参数值。默认是 1 字节，表示只要 Kafka Broker 端积攒了 1 字节的数据，就可以返回给 Consumer 端，这实在是太小了，我们可以让 Broker 端一次性多返回点数据吧


## 调优延时
### Broker 端
1. 增加 num.replica.fetchers 值以加快 Follower 副本的拉取速度，减少整个消息处理的延时

### Producer 端
1. 希望消息尽快地被发送出去，因此不要有过多停留，设置 linger.ms=0
2. 不启用压缩。因为压缩操作本身要消耗 CPU 时间，会增加消息发送的延时
3. 不设置 acks=all。Follower 副本同步往往是降低 Producer 端吞吐量和增加延时的首要原因

### Consumer 端
1. 保持 fetch.min.bytes=1 即可，也就是说，只要 Broker 端有能返回的数据，立即令其返回给 Consumer，缩短 Consumer 消费延时