package com.example.urlshortener.controller;

import com.example.urlshortener.dto.CreateUrlRequest;
import com.example.urlshortener.dto.CreateUrlResponse;
import com.example.urlshortener.dto.UrlStatsResponse;
import com.example.urlshortener.service.UrlService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
public class UrlController {

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
    public ResponseEntity<CreateUrlResponse> update(@PathVariable String code,
                                                      @Valid @RequestBody CreateUrlRequest request) {
        return ResponseEntity.ok(urlService.update(code, request));
    }

    @DeleteMapping("/api/v1/urls/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        urlService.softDelete(code);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/urls/{code}/stats")
    public ResponseEntity<UrlStatsResponse> stats(@PathVariable String code) {
        return ResponseEntity.ok(urlService.getStats(code));
    }

    /**
     * 302 (temporary), not 301: browsers/CDNs cache 301s, so a client's second click never
     * reaches this server again and the click event is lost. 302 forces every click through
     * here so analytics stay accurate.
     */
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String originalUrl = urlService.resolveAndRecordClick(code);
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(originalUrl))
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .build();
    }
}
