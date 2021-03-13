package com.pain.black.kafka.stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.pain.black.kafka.util.Constants;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Produced;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;

public class StreamTest {
    public static void main(String[] args) {
        Logger logger = (Logger) LoggerFactory.getLogger("org.apache.kafka");
        logger.setLevel(Level.INFO);
        logger.setAdditive(true);
        stream();
    }

    private static void stream() {
        Properties properties = new Properties();
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, Constants.BOOTSTRAP_SERVERS_CONFIG);
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "wordCount-app");
        properties.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        properties.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        StreamsBuilder streamsBuilder = new StreamsBuilder();
        countWord(streamsBuilder);
        KafkaStreams kafkaStreams = new KafkaStreams(streamsBuilder.build(), properties);
        kafkaStreams.start();
    }

    /**
     * kafka-console-consumer.sh --bootstrap-server 192.168.100.100:9092 \
     * --topic stream_out \
     * --property print.key=true \
     * --property print.value=true \
     * --property key.deserializer=org.apache.kafka.common.serialization.StringDeserializer \
     * --property value.deserializer=org.apache.kafka.common.serialization.LongSerializer \
     * --from-beginning
     *
     * @param streamsBuilder
     */
    private static void countWord(StreamsBuilder streamsBuilder) {
        KStream<String, String> stream = streamsBuilder.stream(Constants.STREAM_IN_TOPIC);
        KTable<String, Long> kTable = stream.flatMapValues(value -> Arrays.asList(value.toLowerCase(Locale.getDefault()).split(" ")))
                .groupBy((key, value) -> value)
                .count();
        kTable.toStream().to(Constants.STREAM_OUT_TOPIC, Produced.with(Serdes.String(), Serdes.Long()));
    }
}
