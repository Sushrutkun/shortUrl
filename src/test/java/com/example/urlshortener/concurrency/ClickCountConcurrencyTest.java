package com.example.urlshortener.concurrency;

import com.example.urlshortener.entity.ShortUrl;
import com.example.urlshortener.kafka.ClickAggregator;
import com.example.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the two layers that must stay correct under concurrent redirects:
 *
 * 1. ClickAggregator (in-memory buffer, LongAdder) - must not drop increments across threads.
 * 2. ShortUrlRepository.incrementClicks (atomic SQL UPDATE) - must not lose updates when many
 *    threads increment the *same row* concurrently. This is the classic
 *    read-modify-write trap called out in the spec; the atomic UPDATE avoids it entirely
 *    because MySQL/H2 apply "clicks = clicks + 1" as a single row-level operation.
 */
// @DataJpaTest wraps each test in a transaction that rolls back by default, which would keep
// this test's own inserts/updates invisible to the other worker threads' separate connections.
// NOT_SUPPORTED suspends that wrapping transaction so writes actually commit and are visible
// across threads, which is required to genuinely exercise concurrent access to the same row.
@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ClickCountConcurrencyTest {

    @Autowired
    private ShortUrlRepository repository;

    private static final int THREAD_COUNT = 50;
    private static final int INCREMENTS_PER_THREAD = 100;

    @Test
    void atomicIncrementClicks_neverLosesCountsUnderConcurrentLoad() throws InterruptedException {
        ShortUrl shortUrl = ShortUrl.builder()
            .shortCode("concurrent1")
            .originalUrl("https://example.com/hot-link")
            .urlHash("dummy-hash")
            .createdAt(Instant.now())
            .clicks(0L)
            .build();
        repository.saveAndFlush(shortUrl);

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < INCREMENTS_PER_THREAD; j++) {
                        repository.incrementClicks("concurrent1", 1);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(completed).isTrue();

        ShortUrl result = repository.findByShortCodeAndDeletedFalse("concurrent1").orElseThrow();
        assertThat(result.getClicks()).isEqualTo((long) THREAD_COUNT * INCREMENTS_PER_THREAD);
    }

    @Test
    void clickAggregator_neverDropsIncrementsAcrossConcurrentThreads() throws InterruptedException {
        ClickAggregator aggregator = new ClickAggregator();
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < INCREMENTS_PER_THREAD; j++) {
                        aggregator.record("hot-code");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        long total = aggregator.drain().get("hot-code").sum();
        assertThat(total).isEqualTo((long) THREAD_COUNT * INCREMENTS_PER_THREAD);
    }
}
