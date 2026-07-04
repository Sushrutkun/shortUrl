package com.example.urlshortener.kafka;

import com.example.urlshortener.config.KafkaTopicConfig;
import com.example.urlshortener.dto.ClickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Fires one event per redirect, with no partition key (spec: "flush event without any key").
 * acks=all is configured at the producer-factory level (application-local.yml) so a write is only
 * considered successful once all in-sync replicas have it.
 */
@Component
public class ClickEventProducer {

    private static final Logger log = LoggerFactory.getLogger(ClickEventProducer.class);

    private final KafkaTemplate<String, ClickEvent> kafkaTemplate;

    public ClickEventProducer(KafkaTemplate<String, ClickEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishClick(String shortCode) {
        ClickEvent event = new ClickEvent(shortCode, Instant.now());
        kafkaTemplate.send(KafkaTopicConfig.CLICK_EVENTS_TOPIC, event)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    // Redirect has already happened for the user; a lost click event only
                    // means the count under-reports by one - never block the redirect on this.
                    log.warn("Failed to publish click event for code={}", shortCode, ex);
                }
            });
    }
}
