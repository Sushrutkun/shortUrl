package com.example.urlshortener.config;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

/**
 * In-memory Guava bloom filter used as a fast pre-check before hitting the DB on code creation.
 *
 * IMPORTANT: this is a probabilistic "definitely not taken" / "maybe taken" filter, never a
 * source of truth. A "maybe taken" result always falls through to a DB existence check before
 * any 409 is returned (see UrlService). It only supports adds, not removal, so a soft-deleted
 * alias is never freed from the filter - that alias is permanently retired (documented tradeoff,
 * see DESIGN.md).
 *
 * Sized for ~10M expected codes at a 1% false-positive rate. In a multi-instance deployment this
 * would need to be backed by a shared store (e.g. Redis bitfield) instead of per-JVM memory -
 * left as a scaling follow-up (see DESIGN.md "what I'd do next").
 */
@Configuration
public class BloomFilterConfig {

    @Bean
    public BloomFilter<CharSequence> shortCodeBloomFilter() {
        return BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 10_000_000, 0.01);
    }
}
