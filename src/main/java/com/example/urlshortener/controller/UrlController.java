package com.example.urlshortener.controller;

import com.example.urlshortener.dto.CreateUrlRequest;
import com.example.urlshortener.dto.CreateUrlResponse;
import com.example.urlshortener.dto.UrlStatsResponse;
import com.example.urlshortener.service.UrlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@Validated
public class UrlController {

    /**
     * Structurally valid short code: 3-32 chars of base62/-/_ . Superset of both generated
     * 8-char codes and custom aliases. A path variable failing this is rejected with 400
     * (malformed) rather than reaching the DB and returning a misleading 404 (not found).
     */
    private static final String CODE_PATTERN = "^[A-Za-z0-9_-]{3,32}$";
    private static final String CODE_MESSAGE = "code must be 3-32 alphanumeric/-/_ characters";

    private final UrlService urlService;

    public UrlController(UrlService urlService) {
        this.urlService = urlService;
    }

    @PostMapping("/api/v1/urls")
    public ResponseEntity<CreateUrlResponse> shorten(@Valid @RequestBody CreateUrlRequest request) {
        CreateUrlResponse response = urlService.shorten(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/api/v1/urls/{code}")
    public ResponseEntity<CreateUrlResponse> update(
            @PathVariable @Pattern(regexp = CODE_PATTERN, message = CODE_MESSAGE) String code,
            @Valid @RequestBody CreateUrlRequest request) {
        return ResponseEntity.ok(urlService.update(code, request));
    }

    @DeleteMapping("/api/v1/urls/{code}")
    public ResponseEntity<Void> delete(
            @PathVariable @Pattern(regexp = CODE_PATTERN, message = CODE_MESSAGE) String code) {
        urlService.softDelete(code);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/urls/{code}/stats")
    public ResponseEntity<UrlStatsResponse> stats(
            @PathVariable @Pattern(regexp = CODE_PATTERN, message = CODE_MESSAGE) String code) {
        return ResponseEntity.ok(urlService.getStats(code));
    }

    /**
     * 302 (temporary), not 301: browsers/CDNs cache 301s, so a client's second click never
     * reaches this server again and the click event is lost. 302 forces every click through
     * here so analytics stay accurate.
     */
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(
            @PathVariable @Pattern(regexp = CODE_PATTERN, message = CODE_MESSAGE) String code) {
        String originalUrl = urlService.resolveAndRecordClick(code);
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(originalUrl))
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .build();
    }
}
