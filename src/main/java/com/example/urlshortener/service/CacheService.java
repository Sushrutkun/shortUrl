package com.example.urlshortener.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

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

    public void put(String shortCode, String originalUrl) {
        redisTemplate.opsForValue().set(KEY_PREFIX + shortCode, originalUrl, CACHE_TTL);
    }

    public void evict(String shortCode) {
        redisTemplate.delete(KEY_PREFIX + shortCode);
    }
}
