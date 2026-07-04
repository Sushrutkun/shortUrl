package com.example.urlshortener.service;

import com.example.urlshortener.dto.CreateUrlRequest;
import com.example.urlshortener.dto.CreateUrlResponse;
import com.example.urlshortener.entity.ShortUrl;
import com.example.urlshortener.exception.AliasConflictException;
import com.example.urlshortener.exception.UrlNotFoundException;
import com.example.urlshortener.kafka.ClickEventProducer;
import com.example.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UrlServiceTest {

    @Mock private ShortUrlRepository repository;
    @Mock private CacheService cacheService;
    @Mock private ClickEventProducer clickEventProducer;
    @Mock private Environment environment;

    private ShortCodeBloomFilter bloomFilter;
    private CodeGeneratorService codeGeneratorService;
    private UrlService urlService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Exact, stateful stand-in: put()/mightContain() behave like a real filter with no false
        // positives, which is all these tests need (the fall-through path is covered elsewhere).
        bloomFilter = new InMemoryShortCodeBloomFilter();
        codeGeneratorService = new CodeGeneratorService(bloomFilter);
        when(environment.getProperty(eq("app.base-url"), anyString())).thenReturn("http://localhost:8080");

        urlService = new UrlService(repository, codeGeneratorService, cacheService,
            clickEventProducer, bloomFilter, environment);
    }

    // ---------- shorten() ----------

    @Test
    void shorten_createsNewCodeForFreshUrl() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/foo", null, null);
        when(repository.findFirstActiveByUrlHash(anyString())).thenReturn(Optional.empty());
        when(repository.existsByShortCodeAndDeletedFalse(anyString())).thenReturn(false);

        CreateUrlResponse response = urlService.shorten(request);

        assertThat(response.getCode()).hasSize(8);
        assertThat(response.getOriginalUrl()).isEqualTo("https://example.com/foo");
        assertThat(response.getShortUrl()).isEqualTo("http://localhost:8080/" + response.getCode());
        verify(repository).save(any(ShortUrl.class));
    }

    @Test
    void shorten_returnsExistingCodeForDuplicateUrl() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/dup", null, null);
        ShortUrl existing = ShortUrl.builder()
            .shortCode("abc12345")
            .originalUrl("https://example.com/dup")
            .urlHash(CodeGeneratorService.sha256Hex("https://example.com/dup"))
            .createdAt(Instant.now())
            .build();
        when(repository.findFirstActiveByUrlHash(anyString())).thenReturn(Optional.of(existing));

        CreateUrlResponse response = urlService.shorten(request);

        assertThat(response.getCode()).isEqualTo("abc12345");
        verify(repository, never()).save(any(ShortUrl.class));
    }

    @Test
    void shorten_withCustomAlias_usesAliasAsCode() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/promo", "launch2026", null);

        CreateUrlResponse response = urlService.shorten(request);

        assertThat(response.getCode()).isEqualTo("launch2026");
        verify(repository).save(argThat(s -> s.isCustomAlias() && s.getShortCode().equals("launch2026")));
    }

    @Test
    void shorten_withTakenCustomAlias_throws409() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/promo", "taken", null);
        bloomFilter.put("taken");
        when(repository.existsByShortCodeAndDeletedFalse("taken")).thenReturn(true);

        assertThatThrownBy(() -> urlService.shorten(request))
            .isInstanceOf(AliasConflictException.class);

        verify(repository, never()).save(any(ShortUrl.class));
    }

    @Test
    void shorten_withTtl_setsExpiresAt() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/temp", null, Duration.ofDays(7));
        when(repository.findFirstActiveByUrlHash(anyString())).thenReturn(Optional.empty());
        when(repository.existsByShortCodeAndDeletedFalse(anyString())).thenReturn(false);

        CreateUrlResponse response = urlService.shorten(request);

        assertThat(response.getExpiresAt()).isAfter(Instant.now().plus(6, ChronoUnit.DAYS));
    }

    // ---------- resolveAndRecordClick() ----------

    @Test
    void resolve_cacheHit_skipsDbAndPublishesClick() {
        when(cacheService.get("abc")).thenReturn("https://example.com/cached");

        String url = urlService.resolveAndRecordClick("abc");

        assertThat(url).isEqualTo("https://example.com/cached");
        verify(repository, never()).findByShortCodeAndDeletedFalse(anyString());
        verify(clickEventProducer).publishClick("abc");
    }

    @Test
    void resolve_cacheMiss_fallsBackToDbAndCachesResult() {
        when(cacheService.get("xyz")).thenReturn(null);
        ShortUrl shortUrl = ShortUrl.builder()
            .shortCode("xyz")
            .originalUrl("https://example.com/from-db")
            .createdAt(Instant.now())
            .build();
        when(repository.findByShortCodeAndDeletedFalse("xyz")).thenReturn(Optional.of(shortUrl));

        String url = urlService.resolveAndRecordClick("xyz");

        assertThat(url).isEqualTo("https://example.com/from-db");
        verify(cacheService).put("xyz", "https://example.com/from-db");
        verify(clickEventProducer).publishClick("xyz");
    }

    @Test
    void resolve_unknownCode_throws404() {
        when(cacheService.get("missing")).thenReturn(null);
        when(repository.findByShortCodeAndDeletedFalse("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.resolveAndRecordClick("missing"))
            .isInstanceOf(UrlNotFoundException.class);

        verify(clickEventProducer, never()).publishClick(anyString());
    }

    @Test
    void resolve_expiredCode_throws404AndDoesNotCache() {
        when(cacheService.get("expired")).thenReturn(null);
        ShortUrl shortUrl = ShortUrl.builder()
            .shortCode("expired")
            .originalUrl("https://example.com/gone")
            .createdAt(Instant.now().minus(10, ChronoUnit.DAYS))
            .expiresAt(Instant.now().minus(1, ChronoUnit.DAYS))
            .build();
        when(repository.findByShortCodeAndDeletedFalse("expired")).thenReturn(Optional.of(shortUrl));

        assertThatThrownBy(() -> urlService.resolveAndRecordClick("expired"))
            .isInstanceOf(UrlNotFoundException.class);

        verify(cacheService, never()).put(anyString(), anyString());
        verify(clickEventProducer, never()).publishClick(anyString());
    }

    // ---------- update() / softDelete() ----------

    @Test
    void update_evictsCache() {
        ShortUrl shortUrl = ShortUrl.builder()
            .shortCode("abc")
            .originalUrl("https://example.com/old")
            .createdAt(Instant.now())
            .build();
        when(repository.findByShortCodeAndDeletedFalse("abc")).thenReturn(Optional.of(shortUrl));

        urlService.update("abc", new CreateUrlRequest("https://example.com/new", null, null));

        verify(cacheService).evict("abc");
        assertThat(shortUrl.getOriginalUrl()).isEqualTo("https://example.com/new");
    }

    @Test
    void softDelete_marksDeletedAndEvictsCache() {
        ShortUrl shortUrl = ShortUrl.builder()
            .shortCode("abc")
            .originalUrl("https://example.com/old")
            .createdAt(Instant.now())
            .deleted(false)
            .build();
        when(repository.findByShortCodeAndDeletedFalse("abc")).thenReturn(Optional.of(shortUrl));

        urlService.softDelete("abc");

        assertThat(shortUrl.isDeleted()).isTrue();
        verify(cacheService).evict("abc");
    }

    @Test
    void softDelete_unknownCode_throws404() {
        when(repository.findByShortCodeAndDeletedFalse("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.softDelete("missing"))
            .isInstanceOf(UrlNotFoundException.class);
    }
}
