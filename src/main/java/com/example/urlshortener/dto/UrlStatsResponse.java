package com.example.urlshortener.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlStatsResponse {
    private String code;
    private long clicks;
    private Instant createdAt;
    private Instant expiresAt;
    /** True if clicks may lag reality by up to the analytics-consumer batch interval (~1 min). */
    private boolean approximate;
}
