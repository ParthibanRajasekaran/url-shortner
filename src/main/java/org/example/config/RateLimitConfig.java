package org.example.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * In-process rate limiting using Bucket4j's local Bucket API.
 * Each IP address gets its own token bucket (5 POST requests per minute).
 *
 * NGINX provides cross-instance rate limiting at the gateway layer (10 req/min per IP).
 * This layer is an additional app-level guard against abusive clients reaching a single instance.
 */
@Configuration
public class RateLimitConfig {

    public static Bucket createBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(5)
                        .refillIntervally(5, Duration.ofMinutes(1))
                        .build())
                .build();
    }
}
