package com.example.urlshortener.kafka;

import com.example.urlshortener.config.KafkaTopicConfig;
import com.example.urlshortener.dto.ClickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Consumes click events one at a time and buffers them in ClickAggregator; ClickCountFlusher
 * drains that buffer on a schedule and does the actual atomic DB updates (see that class).
 *
 * Reliability: 3 attempts total with exponential backoff, auto-provisioning
 * url.click-events-retry-0 / -retry-1 topics, falling through to url.click-events-dlt
 * (dead-letter topic) if all attempts fail - so a bad/poison message never blocks the partition.
 */
@Component
public class ClickEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ClickEventConsumer.class);

    private final ClickAggregator aggregator;

    public ClickEventConsumer(ClickAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @KafkaListener(topics = KafkaTopicConfig.CLICK_EVENTS_TOPIC, groupId = "analytics-consumer")
    public void onClickEvent(ClickEvent event) {
        aggregator.record(event.getShortCode());
    }

    @DltHandler
    public void onDlt(ClickEvent event) {
        // A click permanently failing to process only means an undercount for that one click -
        // log for visibility/alerting rather than losing it silently.
        log.error("Click event sent to DLT after exhausting retries: {}", event);
    }

    @Configuration
    static class RetryTopicConfig {

        // Retry/DLT topics are auto-provisioned; keep their partition/replication settings in step with
        // the main topic so managed clusters (Aiven: RF >= 2, <= 2 partitions per topic) accept them.
        @Value("${app.kafka.partitions:6}")
        private int partitions;

        @Value("${app.kafka.replication-factor:1}")
        private short replicationFactor;

        @Bean
        public RetryTopicConfiguration clickEventsRetryTopic(KafkaOperations<String, ClickEvent> template) {
            return RetryTopicConfigurationBuilder
                .newInstance()
                .maxAttempts(3)
                .fixedBackOff(Duration.ofSeconds(2).toMillis())
                .autoCreateTopics(true, partitions, replicationFactor)
                .create(template);
        }
    }
}
