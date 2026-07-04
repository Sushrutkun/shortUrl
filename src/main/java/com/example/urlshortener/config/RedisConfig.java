package com.example.urlshortener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis is used purely as a cache-aside layer for code -> originalUrl lookups (5 min TTL)
 * and as the sliding-window counter store for rate limiting.
 *
 * Eviction under memory pressure is handled by Redis itself via
 * `maxmemory-policy allkeys-lru` (set at the Redis server/config level, not in app code) -
 * no custom LRU logic needed here.
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        return template;
    }
}
