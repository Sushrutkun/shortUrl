package com.example.urlshortener.service;

import com.example.urlshortener.exception.CodeGenerationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeGeneratorServiceTest {

    private CodeGeneratorService codeGeneratorService;

    @BeforeEach
    void setUp() {
        // Empty filter: mightContain() is always false, so generate() takes the
        // "definitely not taken" fast path and never touches the DB check.
        codeGeneratorService = new CodeGeneratorService(new InMemoryShortCodeBloomFilter());
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
        // A filter that always answers "maybe taken" forces every candidate through the real DB check.
        CodeGeneratorService generator = new CodeGeneratorService(new SaturatedShortCodeBloomFilter());

        AtomicInteger dbCheckCount = new AtomicInteger(0);
        // DB confirms the first candidate is actually free (false-positive case) -> should succeed.
        String code = generator.generate("https://example.com/a",
            existing -> {
                dbCheckCount.incrementAndGet();
                return false; // not actually taken - the filter's hit was a false positive
            });

        assertThat(code).hasSize(8);
        assertThat(dbCheckCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void throwsAfterExhaustingMaxAttemptsWhenEveryCandidateIsGenuinelyTaken() {
        CodeGeneratorService generator = new CodeGeneratorService(new SaturatedShortCodeBloomFilter());

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
