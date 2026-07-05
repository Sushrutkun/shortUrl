package com.example.urlshortener.dto;

import com.example.urlshortener.config.IstInstantSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateUrlResponse {
    private String code;
    private String shortUrl;
    private String originalUrl;
    @JsonSerialize(using = IstInstantSerializer.class)
    private Instant expiresAt;
}
