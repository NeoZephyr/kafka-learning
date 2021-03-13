package com.pain.black.kafka.admin;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.pain.black.kafka.util.Constants;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.config.ConfigResource;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutionException;

public class AdminTest {

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        Logger logger = (Logger) LoggerFactory.getLogger("org.apache.kafka");
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        createTopic();
        // listTopic();
        // deleteTopic();
        // describeTopics();
        // describeConfig();
        // updatePartition();
    }

    private static AdminClient createAdminClient() {
        Properties properties = new Properties();
        properties.setProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, Constants.BOOTSTRAP_SERVERS_CONFIG);
        return AdminClient.create(properties);
    }

    private static void createTopic() throws ExecutionException, InterruptedException {
        AdminClient adminClient = createAdminClient();
        NewTopic topic = new NewTopic(Constants.STREAM_OUT_TOPIC, 3, (short) 1);
        CreateTopicsResult topicsResult = adminClient.createTopics(Collections.singletonList(topic));
        System.out.println(topicsResult);
        topicsResult.all().get();
    }

    private static void deleteTopic() throws ExecutionException, InterruptedException {
        AdminClient adminClient = createAdminClient();
        DeleteTopicsResult topicsResult = adminClient.deleteTopics(Arrays.asList("test"));
        System.out.println(topicsResult);
        topicsResult.all().get();
    }

    private static void listTopic() throws ExecutionException, InterruptedException {
        AdminClient adminClient = createAdminClient();
        ListTopicsOptions options = new ListTopicsOptions();
        options.listInternal(true);
        ListTopicsResult listTopicsResult = adminClient.listTopics(options);
        Set<String> topics = listTopicsResult.names().get();
        topics.forEach(System.out::println);
    }

    private static void describeTopics() throws ExecutionException, InterruptedException {
        AdminClient adminClient = createAdminClient();
        DescribeTopicsResult topicsResult = adminClient.describeTopics(Arrays.asList("demo"));
        Map<String, TopicDescription> topicToDescribe = topicsResult.all().get();
        topicToDescribe.entrySet().stream().forEach(entry -> {
            System.out.println("key: " + entry.getKey() + ", value: " + entry.getValue());
        });
    }

    private static void updatePartition() throws ExecutionException, InterruptedException {
        AdminClient adminClient = createAdminClient();
        NewPartitions newPartitions = NewPartitions.increaseTo(6);
        CreatePartitionsResult partitionsResult = adminClient.createPartitions(Collections.singletonMap("demo", newPartitions));
        partitionsResult.all().get();
    }

    private static void describeConfig() throws ExecutionException, InterruptedException {
        AdminClient adminClient = createAdminClient();
        ConfigResource configResource = new ConfigResource(ConfigResource.Type.TOPIC, "demo");
        DescribeConfigsResult configsResult = adminClient.describeConfigs(Arrays.asList(configResource));
        Map<ConfigResource, Config> resourceToConfig = configsResult.all().get();
        resourceToConfig.entrySet().stream().forEach(entry -> {
            System.out.println("key: " + entry.getKey() + ", value: " + entry.getValue());
        });
    }

    private static void alterConfig() throws ExecutionException, InterruptedException {
        AdminClient adminClient = createAdminClient();
        Map<ConfigResource, Config> resourceToConfig = new HashMap<>();

        ConfigResource configResource = new ConfigResource(ConfigResource.Type.TOPIC, "demo");
        Config config = new Config(Arrays.asList(new ConfigEntry("preallocate", "true")));
        resourceToConfig.put(configResource, config);
        AlterConfigsResult configsResult = adminClient.alterConfigs(resourceToConfig);
        configsResult.all().get();
    }
}
