package com.example.urlshortener.service;

import com.example.urlshortener.dto.CreateUrlRequest;
import com.example.urlshortener.dto.CreateUrlResponse;
import com.example.urlshortener.dto.UrlStatsResponse;
import com.example.urlshortener.entity.ShortUrl;
import com.example.urlshortener.exception.AliasConflictException;
import com.example.urlshortener.exception.UrlNotFoundException;
import com.example.urlshortener.kafka.ClickEventProducer;
import com.example.urlshortener.repository.ShortUrlRepository;
import com.google.common.hash.BloomFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
public class UrlService {

    private final ShortUrlRepository repository;
    private final CodeGeneratorService codeGeneratorService;
    private final CacheService cacheService;
    private final ClickEventProducer clickEventProducer;
    private final BloomFilter<CharSequence> bloomFilter;
    private final String baseUrl;

    public UrlService(ShortUrlRepository repository,
                       CodeGeneratorService codeGeneratorService,
                       CacheService cacheService,
                       ClickEventProducer clickEventProducer,
                       BloomFilter<CharSequence> bloomFilter,
                       org.springframework.core.env.Environment env) {
        this.repository = repository;
        this.codeGeneratorService = codeGeneratorService;
        this.cacheService = cacheService;
        this.clickEventProducer = clickEventProducer;
        this.bloomFilter = bloomFilter;
        this.baseUrl = env.getProperty("app.base-url", "http://localhost:8080");
    }

    @Transactional
    public CreateUrlResponse shorten(CreateUrlRequest request) {
        String urlHash = CodeGeneratorService.sha256Hex(request.getUrl());

        // Duplicate long URL: return the existing active mapping instead of creating a new one.
        // Requires idx_url_hash (see ShortUrl entity) so this never scans the raw url column.
        if (request.getAlias() == null) {
            Optional<ShortUrl> existing = repository.findFirstActiveByUrlHash(urlHash);
            if (existing.isPresent()) {
                return toCreateResponse(existing.get());
            }
        }

        Instant expiresAt = request.getTtlDays() != null
            ? Instant.now().plus(request.getTtlDays(), ChronoUnit.DAYS)
            : null;

        String code;
        boolean isCustomAlias = request.getAlias() != null;

        if (isCustomAlias) {
            code = request.getAlias();
            boolean maybeTaken = bloomFilter.mightContain(code);
            if (maybeTaken && repository.existsByShortCodeAndDeletedFalse(code)) {
                throw new AliasConflictException(code);
            }
            // Either the bloom filter said "definitely free", or it was a false positive
            // and the DB confirmed the alias is actually free - proceed either way.
        } else {
            code = codeGeneratorService.generate(request.getUrl(), repository::existsByShortCodeAndDeletedFalse);
        }

        ShortUrl shortUrl = ShortUrl.builder()
            .shortCode(code)
            .originalUrl(request.getUrl())
            .urlHash(urlHash)
            .customAlias(isCustomAlias)
            .createdAt(Instant.now())
            .expiresAt(expiresAt)
            .clicks(0L)
            .deleted(false)
            .build();

        repository.save(shortUrl);
        bloomFilter.put(code);

        return toCreateResponse(shortUrl);
    }

    /**
     * @return the original URL to redirect to
     * @throws UrlNotFoundException if the code doesn't exist, is soft-deleted, or has expired
     */
    @Transactional(readOnly = true)
    public String resolveAndRecordClick(String code) {
        String cached = cacheService.get(code);
        String originalUrl;

        if (cached != null) {
            originalUrl = cached;
        } else {
            ShortUrl shortUrl = repository.findByShortCodeAndDeletedFalse(code)
                .orElseThrow(() -> new UrlNotFoundException(code));

            if (shortUrl.isExpired()) {
                throw new UrlNotFoundException(code);
            }

            originalUrl = shortUrl.getOriginalUrl();
            cacheService.put(code, originalUrl);
        }

        // Fire-and-forget: never let analytics plumbing block or fail the redirect itself.
        clickEventProducer.publishClick(code);
        return originalUrl;
    }

    @Transactional(readOnly = true)
    public UrlStatsResponse getStats(String code) {
        ShortUrl shortUrl = repository.findByShortCodeAndDeletedFalse(code)
            .orElseThrow(() -> new UrlNotFoundException(code));

        return UrlStatsResponse.builder()
            .code(shortUrl.getShortCode())
            .clicks(shortUrl.getClicks())
            .createdAt(shortUrl.getCreatedAt())
            .expiresAt(shortUrl.getExpiresAt())
            .approximate(true) // clicks may lag up to the ~60s analytics flush interval
            .build();
    }

    @Transactional
    public CreateUrlResponse update(String code, CreateUrlRequest request) {
        ShortUrl shortUrl = repository.findByShortCodeAndDeletedFalse(code)
            .orElseThrow(() -> new UrlNotFoundException(code));

        shortUrl.setOriginalUrl(request.getUrl());
        shortUrl.setUrlHash(CodeGeneratorService.sha256Hex(request.getUrl()));
        if (request.getTtlDays() != null) {
            shortUrl.setExpiresAt(Instant.now().plus(request.getTtlDays(), ChronoUnit.DAYS));
        }
        repository.save(shortUrl);
        cacheService.evict(code); // stale mapping must not keep serving from cache
        return toCreateResponse(shortUrl);
    }

    @Transactional
    public void softDelete(String code) {
        ShortUrl shortUrl = repository.findByShortCodeAndDeletedFalse(code)
            .orElseThrow(() -> new UrlNotFoundException(code));
        shortUrl.setDeleted(true);
        repository.save(shortUrl);
        cacheService.evict(code); // a deleted link must not keep redirecting from cache
    }

    private CreateUrlResponse toCreateResponse(ShortUrl shortUrl) {
        return CreateUrlResponse.builder()
            .code(shortUrl.getShortCode())
            .shortUrl(baseUrl + "/" + shortUrl.getShortCode())
            .originalUrl(shortUrl.getOriginalUrl())
            .expiresAt(shortUrl.getExpiresAt())
            .build();
    }
}
