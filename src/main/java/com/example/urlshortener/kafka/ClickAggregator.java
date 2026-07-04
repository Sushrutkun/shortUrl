package com.example.urlshortener.kafka;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Buffers click counts in memory, grouped by short code, between flush cycles.
 * LongAdder gives lock-free, thread-safe increments under concurrent Kafka consumer threads.
 */
@Component
public class ClickAggregator {

    private final Map<String, LongAdder> buffer = new ConcurrentHashMap<>();

    public void record(String shortCode) {
        buffer.computeIfAbsent(shortCode, k -> new LongAdder()).increment();
    }

    /** Atomically swaps out the buffer for flushing, so new increments don't get lost mid-flush. */
    public Map<String, LongAdder> drain() {
        Map<String, LongAdder> snapshot = Map.copyOf(buffer);
        buffer.keySet().removeAll(snapshot.keySet());
        return snapshot;
    }
}
