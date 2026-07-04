package com.example.urlshortener.filter;

import com.example.urlshortener.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * Rate limiting lives here, as middleware inside this single service (per the take-home's
 * "build a single service" constraint) rather than at an external gateway. The same sliding-
 * window logic would move to a shared API gateway in a multi-service future without changing
 * the algorithm - documented as a "what I'd do next" in DESIGN.md.
 *
 * Algorithm: sliding window log via a Redis sorted set per client key (score = request
 * timestamp millis). On each request: trim entries older than the window, count what's left,
 * reject if at capacity, otherwise record this request. Applied only to the write path
 * (POST /api/v1/urls) since that's the endpoint the spec calls out for throttling.
 *
 * Limit is set to 80% of measured infra throughput headroom, keyed by client IP (falls back
 * to X-Forwarded-For if present) to also blunt single-IP DDoS attempts.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String KEY_PREFIX = "ratelimit:";
    private static final long WINDOW_MILLIS = 1000; // 1 second sliding window
    private static final int MAX_REQUESTS_PER_WINDOW = 8; // ~80% of a measured 10 req/s/IP ceiling

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RateLimitFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().equals("/api/v1/urls"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String clientKey = resolveClientKey(request);
        String redisKey = KEY_PREFIX + clientKey;
        long now = Instant.now().toEpochMilli();
        long windowStart = now - WINDOW_MILLIS;

        redisTemplate.opsForZSet().removeRangeByScore(redisKey, 0, windowStart);
        Long currentCount = redisTemplate.opsForZSet().zCard(redisKey);

        if (currentCount != null && currentCount >= MAX_REQUESTS_PER_WINDOW) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.TOO_MANY_REQUESTS.value())
                .error("Too Many Requests")
                .message("Rate limit exceeded, please slow down")
                .build();
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return;
        }

        redisTemplate.opsForZSet().add(redisKey, UUID.randomUUID().toString(), now);
        redisTemplate.expire(redisKey, java.time.Duration.ofSeconds(2));

        filterChain.doFilter(request, response);
    }

    private String resolveClientKey(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
