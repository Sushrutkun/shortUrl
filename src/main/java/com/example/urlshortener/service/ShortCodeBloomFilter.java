package com.example.urlshortener.service;

/**
 * Fast, probabilistic "definitely not taken" / "maybe taken" pre-check used before a real DB
 * existence check on code/alias creation (see {@link CodeGeneratorService} and {@link UrlService}).
 *
 * Two implementations exist:
 * - {@link RedisBloomFilter} (production, {@code @Profile("!test")}): backed by a Redis bitfield
 *   so the filter is shared across every instance of this service.
 * - the in-memory test fake ({@code @Profile("test")}): an exact Set-backed stand-in so tests
 *   don't need a real Redis connection and stay deterministic.
 *
 * A "maybe taken" ({@code true}) result must never be treated as a source of truth by itself -
 * callers always confirm with a real DB check before rejecting a code/alias as taken.
 */
public interface ShortCodeBloomFilter {

    boolean mightContain(String value);

    void put(String value);
}
