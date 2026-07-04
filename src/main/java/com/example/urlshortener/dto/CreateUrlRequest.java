package com.example.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.time.DurationMin;

import java.time.Duration;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateUrlRequest {

    @NotBlank(message = "url must not be blank")
    @Pattern(regexp = "^https?://.+", message = "url must be a valid http(s) URL")
    private String url;

    /** Optional custom alias, e.g. "launch2026". Alphanumeric + hyphen/underscore only. */
    @Pattern(regexp = "^[A-Za-z0-9_-]{3,32}$", message = "alias must be 3-32 alphanumeric/-/_ characters")
    private String alias;

    /** Optional time-to-live as an ISO-8601 duration, e.g. "PT10S", "PT1M", "P2D". */
    @DurationMin(seconds = 1, message = "ttl must be a positive ISO-8601 duration (e.g. PT10S, PT1M, P2D)")
    private Duration ttl;
}
