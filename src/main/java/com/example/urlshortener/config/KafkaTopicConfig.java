package com.example.urlshortener.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    public static final String CLICK_EVENTS_TOPIC = "url.click-events";

    @Bean
    public NewTopic clickEventsTopic() {
        return TopicBuilder.name(CLICK_EVENTS_TOPIC)
                .partitions(6)
                .replicas(1) // bump to 3 in a real multi-broker cluster
                .build();
        // Retry topics (url.click-events-retry-0, -retry-1) and the DLT
        // (url.click-events-dlt) are auto-provisioned by @RetryableTopic on the consumer.
    }
}
