package com.example.urlshortener.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class CacheService {

    private static final String KEY_PREFIX = "url:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;

    public CacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String get(String shortCode) {
        return redisTemplate.opsForValue().get(KEY_PREFIX + shortCode);
    }

    /**
     * Cache the mapping, capping the Redis TTL to the link's remaining lifetime so that an
     * expiring link is never served from cache past its expiry time. Without this, a 30-second
     * TTL link could still redirect for up to 5 minutes if it happened to be cached just before
     * expiry.
     */
    public void put(String shortCode, String originalUrl, Instant expiresAt) {
        Duration ttl = CACHE_TTL;
        if (expiresAt != null) {
            Duration remaining = Duration.between(Instant.now(), expiresAt);
            if (remaining.isNegative() || remaining.isZero()) {
                return; // already expired, don't cache
            }
            if (remaining.compareTo(CACHE_TTL) < 0) {
                ttl = remaining;
            }
        }
        redisTemplate.opsForValue().set(KEY_PREFIX + shortCode, originalUrl, ttl);
    }

    public void evict(String shortCode) {
        redisTemplate.delete(KEY_PREFIX + shortCode);
    }
}
