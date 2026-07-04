package com.example.urlshortener.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only stand-in for {@link RedisBloomFilter}: an exact (non-probabilistic), thread-safe
 * Set-backed implementation, active under the "test" profile so the Spring test context (and any
 * plain unit test that constructs one directly) doesn't need a real Redis connection.
 *
 * Being exact rather than probabilistic is fine here - it's a superset of what real Bloom filter
 * behavior guarantees (no false positives instead of the ~1% the real filter allows), so tests
 * that exercise the "maybe taken -> confirm via DB" fallback path use
 * {@link SaturatedShortCodeBloomFilter} instead, which deliberately always answers "maybe taken".
 */
@Component
@Profile("test")
public class InMemoryShortCodeBloomFilter implements ShortCodeBloomFilter {

    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    @Override
    public boolean mightContain(String value) {
        return seen.contains(value);
    }

    @Override
    public void put(String value) {
        seen.add(value);
    }
}
