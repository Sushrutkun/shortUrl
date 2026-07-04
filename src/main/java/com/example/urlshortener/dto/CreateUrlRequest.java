package com.example.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    @Positive(message = "ttlDays must be positive")
    private Integer ttlDays;
}
