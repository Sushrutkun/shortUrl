package com.example.urlshortener.service;

import com.example.urlshortener.exception.CodeGenerationException;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeGeneratorServiceTest {

    private BloomFilter<CharSequence> bloomFilter;
    private CodeGeneratorService codeGeneratorService;

    @BeforeEach
    void setUp() {
        bloomFilter = BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 10_000, 0.01);
        codeGeneratorService = new CodeGeneratorService(bloomFilter);
    }

    @Test
    void generatesAnEightCharacterCode() {
        String code = codeGeneratorService.generate("https://example.com/a", existing -> false);
        assertThat(code).hasSize(8);
        assertThat(code).matches("[0-9a-zA-Z]{8}");
    }

    @Test
    void differentUuidsProduceDifferentCodesForSameUrl() {
        String code1 = codeGeneratorService.generate("https://example.com/a", existing -> false);
        String code2 = codeGeneratorService.generate("https://example.com/a", existing -> false);
        // Not guaranteed different by contract, but overwhelmingly likely given SHA-256 + fresh UUID.
        assertThat(code1).isNotEqualTo(code2);
    }

    @Test
    void fallsThroughToDbCheckWhenBloomFilterReportsPossibleMatch() {
        // Tiny, near-saturated filter: mightContain() returns true for virtually any input,
        // simulating the "maybe taken" case that must fall through to a real DB check.
        BloomFilter<CharSequence> saturatedFilter =
            BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 1, 0.5);
        saturatedFilter.put("seed");
        CodeGeneratorService generator = new CodeGeneratorService(saturatedFilter);

        AtomicInteger dbCheckCount = new AtomicInteger(0);
        // DB confirms the first candidate is actually free (false positive case) -> should succeed.
        String code = generator.generate("https://example.com/a",
            existing -> {
                dbCheckCount.incrementAndGet();
                return false; // not actually taken - bloom filter's hit was a false positive
            });

        assertThat(code).hasSize(8);
        assertThat(dbCheckCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void throwsAfterExhaustingMaxAttemptsWhenEveryCandidateIsGenuinelyTaken() {
        BloomFilter<CharSequence> saturatedFilter =
            BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 1, 0.5);
        saturatedFilter.put("seed");
        CodeGeneratorService generator = new CodeGeneratorService(saturatedFilter);

        assertThatThrownBy(() ->
            generator.generate("https://example.com/a", existing -> true) // DB always says taken
        ).isInstanceOf(CodeGenerationException.class);
    }

    @Test
    void sha256HexIsDeterministicForDedupLookup() {
        String hash1 = CodeGeneratorService.sha256Hex("https://example.com/same-url");
        String hash2 = CodeGeneratorService.sha256Hex("https://example.com/same-url");
        String hash3 = CodeGeneratorService.sha256Hex("https://example.com/different-url");

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).isNotEqualTo(hash3);
        assertThat(hash1).hasSize(64); // hex-encoded SHA-256
    }
}
