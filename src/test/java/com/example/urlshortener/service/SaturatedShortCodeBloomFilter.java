package com.example.urlshortener.service;

/**
 * Test double that always reports "maybe taken", simulating a saturated/false-positive Bloom
 * filter result so tests can exercise the "fall through to a real DB check" path in
 * {@link CodeGeneratorService} and {@link UrlService} without needing a genuinely full filter.
 */
public class SaturatedShortCodeBloomFilter implements ShortCodeBloomFilter {

    @Override
    public boolean mightContain(String value) {
        return true;
    }

    @Override
    public void put(String value) {
        // no-op - this fake only needs to answer "maybe taken" for every query
    }
}
