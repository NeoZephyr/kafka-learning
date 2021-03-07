package com.pain.black.kafka.consumer;

import com.pain.black.kafka.util.Constants;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConsumerRunner implements Runnable {

    private final AtomicBoolean start = new AtomicBoolean(false);
    private final KafkaConsumer consumer;
    private final String topic;

    ConsumerRunner(String topic) {
        this.topic = topic;
        String groupId = String.format("%s_group",this.topic);
        Properties properties = new Properties();
        properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Constants.BOOTSTRAP_SERVERS_CONFIG);
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        // properties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        // properties.setProperty(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");
        properties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        properties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");

        consumer = new KafkaConsumer<>(properties);
        consumer.subscribe(Arrays.asList(this.topic));
    }

    @Override
    public void run() {
        if (start.get()) {
        }
    }

    public void shutdown() {
        start.set(false);
        consumer.wakeup();
    }
}
