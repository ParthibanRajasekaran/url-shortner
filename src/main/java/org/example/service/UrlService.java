package org.example.service;

import io.seruco.encoding.base62.Base62;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.config.AppProperties;
import org.example.dto.ShortenRequest;
import org.example.dto.ShortenResponse;
import org.example.entity.UrlMapping;
import org.example.exception.RedirectLoopException;
import org.example.repository.UrlMappingRepository;
import org.example.util.SnowflakeIdGenerator;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class UrlService {

    private static final Pattern SHORT_CODE_PATTERN = Pattern.compile("^[a-zA-Z0-9]{1,7}$");
    private static final Base62 BASE62 = Base62.createInstance();

    private final UrlMappingRepository repository;
    private final RedissonClient redissonClient;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final AppProperties appProperties;

    public ShortenResponse shortenUrl(ShortenRequest request) {
        String longUrl = request.longUrl();

        // Redirect loop detection
        String host = extractHost(longUrl);
        if (host != null && host.equalsIgnoreCase(appProperties.getOwnHost())) {
            throw new RedirectLoopException(longUrl);
        }

        // Generate ID and encode to Base62 (7 chars)
        long id = snowflakeIdGenerator.nextId();
        byte[] bytes = ByteBuffer.allocate(8).putLong(id).array();
        String shortCode = new String(BASE62.encode(bytes)).substring(0, 7);

        // Persist to DB
        UrlMapping entity = UrlMapping.builder()
                .id(id)
                .shortCode(shortCode)
                .longUrl(longUrl)
                .build();
        entity = repository.save(entity);

        // Write-through to Redis (non-blocking on failure)
        writeToCache(shortCode, longUrl);

        return new ShortenResponse(
                shortCode,
                appProperties.getBaseUrl() + "/" + shortCode,
                longUrl,
                entity.getCreatedAt()
        );
    }

    public String resolveUrl(String shortCode) {
        // Validate short code format
        if (!SHORT_CODE_PATTERN.matcher(shortCode).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Short code not found");
        }

        // Check negative cache
        if (isNegativelyCached(shortCode)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Short code not found");
        }

        // Check positive cache
        String cached = getFromCache(shortCode);
        if (cached != null) {
            return cached;
        }

        // DB fallback
        return repository.findByShortCode(shortCode)
                .map(mapping -> {
                    writeToCache(shortCode, mapping.getLongUrl());
                    return mapping.getLongUrl();
                })
                .orElseGet(() -> {
                    writeNegativeCache(shortCode);
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Short code not found");
                });
    }

    private void writeToCache(String shortCode, String longUrl) {
        try {
            RBucket<String> bucket = redissonClient.getBucket("url:" + shortCode);
            bucket.set(longUrl, appProperties.getRedis().getUrlTtlSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis write failed for shortCode={}: {}", shortCode, e.getMessage());
        }
    }

    private String getFromCache(String shortCode) {
        try {
            RBucket<String> bucket = redissonClient.getBucket("url:" + shortCode);
            return bucket.get();
        } catch (Exception e) {
            log.warn("Redis read failed for shortCode={}: {}", shortCode, e.getMessage());
            return null;
        }
    }

    private boolean isNegativelyCached(String shortCode) {
        try {
            RBucket<String> bucket = redissonClient.getBucket("404:" + shortCode);
            return bucket.isExists();
        } catch (Exception e) {
            log.warn("Redis negative cache check failed for shortCode={}: {}", shortCode, e.getMessage());
            return false;
        }
    }

    private void writeNegativeCache(String shortCode) {
        try {
            RBucket<String> bucket = redissonClient.getBucket("404:" + shortCode);
            bucket.set("1", appProperties.getRedis().getNotFoundTtlSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis negative cache write failed for shortCode={}: {}", shortCode, e.getMessage());
        }
    }

    private String extractHost(String url) {
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
