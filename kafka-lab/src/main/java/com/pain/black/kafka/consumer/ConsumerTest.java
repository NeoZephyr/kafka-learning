package com.pain.black.kafka.consumer;

import com.pain.black.kafka.util.Constants;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.*;

public class ConsumerTest {
    public static void main(String[] args) {
        consume();
    }

    private static void consume() {
        Properties properties = new Properties();
        properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Constants.BOOTSTRAP_SERVERS_CONFIG);
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, Constants.DEMO_TOPIC_CONSUMER_GROUP);
        // properties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        // properties.setProperty(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");
        properties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        properties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
        consumer.subscribe(Arrays.asList(Constants.DEMO_TOPIC));

        // 消费指定 topic
        // consumer.assign(Arrays.asList(new TopicPartition(Constants.DEMO_TOPIC, 1)));

        // 手动控制消费
        // 1. 从 redis 读取 offset
        // 2. consumer seek 到指定 offset
        // 3. consumer poll
        // 4. 消费之后更新 offset

        while (true) {
            // 手动指定消费位置
            consumer.seek(new TopicPartition(Constants.DEMO_TOPIC, 0), 0);
            ConsumerRecords<String, String> consumerRecords = consumer.poll(Duration.ofMillis(1000));

            consumerRecords.partitions().forEach(topicPartition -> {
                List<ConsumerRecord<String, String>> partitionRecords = consumerRecords.records(topicPartition);
                partitionRecords.forEach(record -> {
                    System.out.printf("consume message, partition: %d, offset: %d, key: %s, value: %s\n",
                            record.partition(), record.offset(), record.key(), record.value());
                });

                long lastOffset = partitionRecords.get(partitionRecords.size() - 1).offset();
                Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
                offsets.put(topicPartition, new OffsetAndMetadata(lastOffset + 1));
                consumer.commitSync(offsets);
            });

            // consumer.commitAsync();
        }
    }
}
