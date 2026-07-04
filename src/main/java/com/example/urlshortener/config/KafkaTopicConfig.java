package com.example.urlshortener.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    public static final String CLICK_EVENTS_TOPIC = "url.click-events";

    // 1 for a local single-broker cluster; managed clusters (e.g. Aiven) require >= 2.
    @Value("${app.kafka.replication-factor:1}")
    private short replicationFactor;

    // 6 locally; managed free/trial plans cap partitions per topic (Aiven free plan allows 2).
    @Value("${app.kafka.partitions:6}")
    private int partitions;

    @Bean
    public NewTopic clickEventsTopic() {
        return TopicBuilder.name(CLICK_EVENTS_TOPIC)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
        // Retry topics (url.click-events-retry-0, -retry-1) and the DLT
        // (url.click-events-dlt) are auto-provisioned by @RetryableTopic on the consumer.
    }
}
