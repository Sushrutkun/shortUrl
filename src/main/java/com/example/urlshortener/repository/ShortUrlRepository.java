package com.example.urlshortener.repository;

import com.example.urlshortener.entity.ShortUrl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {

    Optional<ShortUrl> findByShortCodeAndDeletedFalse(String shortCode);

    /**
     * Returns the first non-deleted, non-expired mapping for a given long URL.
     * Used to satisfy "resubmitting the same URL returns the existing code" (see DESIGN.md).
     * Backed by idx_url_hash so this never scans the raw url text column.
     */
    @Query("select s from ShortUrl s where s.urlHash = :urlHash and s.deleted = false "
         + "and (s.expiresAt is null or s.expiresAt > CURRENT_TIMESTAMP) "
         + "order by s.createdAt asc")
    Optional<ShortUrl> findFirstActiveByUrlHash(@Param("urlHash") String urlHash);

    boolean existsByShortCodeAndDeletedFalse(String shortCode);

    /**
     * Atomic counter increment: UPDATE ... SET clicks = clicks + ? avoids the classic
     * read-modify-write race under concurrent redirects to the same code.
     */
    @Modifying
    @Transactional
    @Query("update ShortUrl s set s.clicks = s.clicks + :delta where s.shortCode = :shortCode")
    int incrementClicks(@Param("shortCode") String shortCode, @Param("delta") long delta);
}
