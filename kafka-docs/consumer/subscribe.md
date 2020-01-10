## 订阅主题和分区
集合方式
```java
consumer.subscribe(Arrays.asList(topic1));
consumer.subscribe(Arrays.asList(topic2));
```
正则表达式方式
```java
consumer.subscribe(Pattern.compile("topic-.*"));
```
直接订阅某些主题的特定分区
```java
consumer.assign(Arrays.asList(new TopicPartition(TOPIC, 0)));
```

集合订阅的方式、正则表达式订阅的方式和指定分区的订阅方式分表代表了三种不同的订阅状态：AUTO_TOPICS、AUTO_PATTERN 和 USER_ASSIGNED（如果没有订阅，那么订阅状态为 NONE）。这三种状态是互斥的，在一个消费者中只能使用其中的一种

通过 subscribe() 方法订阅主题具有消费者自动再均衡的功能，在多个消费者的情况下可以根据分区分配策略来自动分配各个消费者与分区的关系。当消费组内的消费者增加或减少时，分区分配关系会自动调整，以实现消费负载均衡及故障自动转移。而通过 assign() 方法订阅分区时，是不具备消费者自动均衡的功能的


## 取消订阅
既可以取消通过集合方式实现的订阅，也可以取消通过正则表达式方式实现的订阅，还可以取消通过直接订阅方式实现的订阅
```java
consumer.unsubscribe();
consumer.subscribe(new ArrayList<String>());
consumer.assign(new ArrayList<TopicPartition>());
```

## 消费者组
对于消息中间件而言，一般有两种消息投递模式：点对点模式和发布/订阅模式。Kafka 同时支持两种消息投递模式：
1. 如果所有的消费者都隶属于同一个消费组，那么所有的消息都会被均衡地投递给每一个消费者，即每条消息只会被一个消费者处理，相当于点对点模式的应用
2. 如果所有的消费者都隶属于不同的消费组，那么所有的消息都会被广播给所有的消费者，即每条消息会被所有的消费者处理，相当于发布/订阅模式的应用