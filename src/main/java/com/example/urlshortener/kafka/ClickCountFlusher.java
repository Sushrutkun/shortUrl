package com.example.urlshortener.kafka;

import com.example.urlshortener.repository.ShortUrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runs every 60s: drains the in-memory click buffer and issues one atomic
 * `UPDATE clicks = clicks + N` per code (see ShortUrlRepository.incrementClicks).
 *
 * This is the source of the documented staleness tradeoff: GET /stats can lag real
 * click activity by up to this interval. Traded deliberately for write efficiency -
 * one UPDATE per code per minute instead of one UPDATE per click.
 */
@Component
public class ClickCountFlusher {

    private static final Logger log = LoggerFactory.getLogger(ClickCountFlusher.class);

    private final ClickAggregator aggregator;
    private final ShortUrlRepository repository;

    public ClickCountFlusher(ClickAggregator aggregator, ShortUrlRepository repository) {
        this.aggregator = aggregator;
        this.repository = repository;
    }

    @Scheduled(fixedRate = 60_000)
    public void flush() {
        Map<String, LongAdder> snapshot = aggregator.drain();
        if (snapshot.isEmpty()) {
            return;
        }
        for (Map.Entry<String, LongAdder> entry : snapshot.entrySet()) {
            try {
                repository.incrementClicks(entry.getKey(), entry.getValue().sum());
            } catch (Exception e) {
                // Don't let one bad code abort the whole batch.
                log.error("Failed to flush click count for code={}", entry.getKey(), e);
            }
        }
    }
}
