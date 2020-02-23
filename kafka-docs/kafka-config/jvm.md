## jvm 参数
KAFKA_HEAP_OPTS：指定堆大小，默认 1GB
KAFKA_JVM_PERFORMANCE_OPTS：指定 GC 参数

```sh
export KAFKA_HEAP_OPTS=--Xms6g  --Xmx6g
export KAFKA_JVM_PERFORMANCE_OPTS= -server -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:InitiatingHeapOccupancyPercent=35 -XX:+ExplicitGCInvokesConcurrent -Djava.awt.headless=true
```