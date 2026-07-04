package com.example.urlshortener.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * short_code -> unique + indexed: hottest lookup path (GET /{code}) needs O(1)/O(log n) access.
 * url_hash   -> indexed: dedup lookup for "same long URL submitted twice" without scanning
 *               the raw url text column (which can be up to 2048 chars).
 */
@Entity
@Table(
    name = "short_urls",
    indexes = {
        @Index(name = "idx_short_code", columnList = "short_code", unique = true),
        @Index(name = "idx_url_hash", columnList = "url_hash")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShortUrl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_code", nullable = false, unique = true, length = 16)
    private String shortCode;

    @Column(name = "original_url", nullable = false, length = 2048)
    private String originalUrl;

    /** SHA-256 hex digest of originalUrl. Used for dedup lookups instead of scanning the url column. */
    @Column(name = "url_hash", nullable = false, length = 64)
    private String urlHash;

    @Column(name = "custom_alias", nullable = false)
    @Builder.Default
    private boolean customAlias = false;

    @Column(name = "clicks", nullable = false)
    @Builder.Default
    private long clicks = 0L;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Nullable: absence means the link never expires. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Soft delete flag - DELETE endpoint sets this instead of removing the row. */
    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
