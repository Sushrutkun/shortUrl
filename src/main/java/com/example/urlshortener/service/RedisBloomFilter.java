package com.example.urlshortener.service;

import com.google.common.hash.Hashing;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Bloom filter backed by a single Redis string used as a bitfield (SETBIT/GETBIT), so every
 * instance of this service shares one filter instead of each pod keeping its own in-memory copy.
 * This replaces the earlier per-JVM Guava {@code BloomFilter} (see DESIGN.md "what I'd do next").
 *
 * Sizing: for an expected 10,000,000 entries at a 1% false-positive rate, the standard optimal
 * formulas give:
 *   m (bits)          = ceil(-n * ln(p) / (ln 2)^2)  ~= 95,850,584 bits (~11.4 MB as one Redis key)
 *   k (hash functions) = round((m / n) * ln 2)        ~= 7
 *
 * Hashing: a single Murmur3-128 hash of the value is split into two independent 64-bit halves
 * (hash1, hash2); the k bit positions are hash1 + i*hash2 (mod m) for i in 0..k-1. This is the
 * standard Kirsch-Mitzenmacher double-hashing trick - the same technique Guava's own BloomFilter
 * uses internally - so we get k effectively-independent hashes from one hash computation instead
 * of running k separate hash functions.
 *
 * Both the add and the check are done as a single pipelined round trip to Redis (one command per
 * bit position, all sent together), not k sequential round trips.
 *
 * As with the old in-memory version, this only supports add, not remove: a soft-deleted code/alias
 * is never freed from the filter (documented tradeoff in DESIGN.md). And, as always, a "maybe
 * taken" result here is never trusted on its own - callers still confirm against the DB.
 */
@Component
@Profile("!test")
public class RedisBloomFilter implements ShortCodeBloomFilter {

    private static final byte[] KEY = "bloom:short_codes".getBytes(StandardCharsets.UTF_8);
    private static final long NUM_BITS = 95_850_584L;
    private static final int NUM_HASHES = 7;

    private final StringRedisTemplate redisTemplate;

    public RedisBloomFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void put(String value) {
        long[] positions = bitPositions(value);
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (long position : positions) {
                connection.stringCommands().setBit(KEY, position, true);
            }
            return null;
        });
    }

    @Override
    public boolean mightContain(String value) {
        long[] positions = bitPositions(value);
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (long position : positions) {
                connection.stringCommands().getBit(KEY, position);
            }
            return null;
        });

        for (Object bitIsSet : results) {
            if (!Boolean.TRUE.equals(bitIsSet)) {
                return false; // any unset bit -> definitely not present
            }
        }
        return true; // every bit set -> maybe present (caller must confirm against the DB)
    }

    private long[] bitPositions(String value) {
        byte[] hash = Hashing.murmur3_128().hashString(value, StandardCharsets.UTF_8).asBytes();
        long hash1 = toLong(hash, 0);
        long hash2 = toLong(hash, 8);

        long[] positions = new long[NUM_HASHES];
        long combined = hash1;
        for (int i = 0; i < NUM_HASHES; i++) {
            positions[i] = Math.floorMod(combined, NUM_BITS);
            combined += hash2;
        }
        return positions;
    }

    private static long toLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xFF);
        }
        return value;
    }
}
