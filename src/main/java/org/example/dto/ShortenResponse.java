package org.example.dto;

import java.time.Instant;

public record ShortenResponse(
        String shortCode,
        String shortUrl,
        String longUrl,
        Instant createdAt
) {}
