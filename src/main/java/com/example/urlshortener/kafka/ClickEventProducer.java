package com.example.urlshortener.kafka;

import com.example.urlshortener.config.KafkaTopicConfig;
import com.example.urlshortener.dto.ClickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

    /**
     * Warm the producer once the app is ready. The first send() otherwise pays the full
     * cold-start cost - TLS + SASL handshake, metadata fetch and INIT_PRODUCER_ID - which against
     * the remote Aiven cluster takes ~2s and can exceed max.block.ms, dropping the first real
     * click. partitionsFor() forces that initialization here, at startup, so user clicks hit an
     * already-initialized producer with cached metadata and never block. Best-effort: a failure
     * here (broker down at boot) is logged, not fatal - the producer will initialize on first use.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        try {
            kafkaTemplate.partitionsFor(KafkaTopicConfig.CLICK_EVENTS_TOPIC);
            log.info("Kafka producer warmed up for topic {}", KafkaTopicConfig.CLICK_EVENTS_TOPIC);
        } catch (Exception e) {
            log.warn("Kafka producer warm-up failed; first click event may be delayed", e);
        }
    }

    public void publishClick(String shortCode) {
        ClickEvent event = new ClickEvent(shortCode, Instant.now());
        try {
            kafkaTemplate.send(KafkaTopicConfig.CLICK_EVENTS_TOPIC, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        // Redirect has already happened for the user; a lost click event only
                        // means the count under-reports by one - never block the redirect on this.
                        log.warn("Failed to publish click event for code={}", shortCode, ex);
                    }
                });
        } catch (Exception e) {
            // send() can throw synchronously (before returning a future) when it can't fetch
            // cluster metadata within max.block.ms - e.g. broker unreachable or auth failing.
            // Analytics is best-effort; a broken Kafka must never turn a redirect into a 500.
            log.warn("Failed to enqueue click event for code={}", shortCode, e);
        }
    }
}
