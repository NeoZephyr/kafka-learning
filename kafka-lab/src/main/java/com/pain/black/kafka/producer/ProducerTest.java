package com.pain.black.kafka.producer;

import com.pain.black.kafka.util.Constants;
import org.apache.kafka.clients.producer.*;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class ProducerTest {
    public static void main(String[] args) throws ExecutionException, InterruptedException {
        sendMessage();
    }

    private static void sendMessage() throws ExecutionException, InterruptedException {
        Properties properties = new Properties();
        properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Constants.BOOTSTRAP_SERVERS_CONFIG);
        properties.setProperty(ProducerConfig.ACKS_CONFIG, "all");
        properties.setProperty(ProducerConfig.RETRIES_CONFIG, "0");
        properties.setProperty(ProducerConfig.BATCH_SIZE_CONFIG, "16384");
        properties.setProperty(ProducerConfig.LINGER_MS_CONFIG, "1");
        properties.setProperty(ProducerConfig.BUFFER_MEMORY_CONFIG, "33554432");

        // properties.setProperty(ProducerConfig.PARTITIONER_CLASS_CONFIG, "");
        properties.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        properties.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");

        Producer<String, String> producer = new KafkaProducer<>(properties);

        for (int i = 0; i < 10; i++) {
            ProducerRecord<String, String> record = new ProducerRecord<>(Constants.DEMO_TOPIC, UUID.randomUUID().toString(), String.valueOf(i));
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    System.out.println("error: " + exception.getMessage());
                } else {
                    System.out.printf("partition: %d, offset: %d\n", metadata.partition(), metadata.offset());
                }
            });
        }

        producer.close();
    }
}
