package org.example.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-process rate limiting using Bucket4j's local Bucket API.
 * Each IP address gets its own token bucket (5 POST requests per minute).
 *
 * NGINX provides cross-instance rate limiting at the gateway layer (10 req/min per IP).
 * This layer is an additional app-level guard against abusive clients reaching a single instance.
 */
@Configuration
public class RateLimitConfig {

    /**
     * Per-IP bucket registry. Buckets are created lazily on first request from an IP.
     * Old entries are never explicitly evicted here; for production, swap with a
     * Caffeine-backed expiring map to avoid unbounded growth.
     */
    public static Bucket createBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(5)
                        .refillIntervally(5, Duration.ofMinutes(1))
                        .build())
                .build();
    }

    public ConcurrentMap<String, Bucket> ipRateBuckets() {
        return new ConcurrentHashMap<>();
    }
}
