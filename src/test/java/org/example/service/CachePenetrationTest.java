package org.example.service;

import org.example.config.AppProperties;
import org.example.entity.UrlMapping;
import org.example.repository.UrlMappingRepository;
import org.example.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
class CachePenetrationTest {

    @Mock
    private UrlMappingRepository repository;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private UrlService urlService;

    @BeforeEach
    void setUp() {
        urlService = new UrlService(repository, redissonClient, snowflakeIdGenerator, new AppProperties());
    }

    @Test
    void testNegativeCache_preventsDatabaseHammeringAfterFirstMiss() {
        RBucket negBucket = mock(RBucket.class);
        RBucket posBucket = mock(RBucket.class);
        doReturn(negBucket).when(redissonClient).getBucket("404:missing");
        doReturn(posBucket).when(redissonClient).getBucket("url:missing");

        // First call: both caches empty, DB also misses
        when(negBucket.isExists()).thenReturn(false);
        when(posBucket.get()).thenReturn(null);
        when(repository.findByShortCode("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.resolveUrl("missing"))
                .isInstanceOf(ResponseStatusException.class);

        // Simulate negative cache now populated (as written by the first call)
        when(negBucket.isExists()).thenReturn(true);

        // Second call - negative cache hit, DB must NOT be called again
        assertThatThrownBy(() -> urlService.resolveUrl("missing"))
                .isInstanceOf(ResponseStatusException.class);

        verify(repository, atMostOnce()).findByShortCode("missing");
    }

    @Test
    void testRedisDown_fallsBackToDatabase_noExceptionThrown() {
        // Every Redis call throws
        doThrow(new RuntimeException("Redis connection refused")).when(redissonClient).getBucket(anyString());

        UrlMapping mapping = UrlMapping.builder()
                .id(1L).shortCode("abc1234").longUrl("https://example.com").createdAt(Instant.now()).build();
        when(repository.findByShortCode("abc1234")).thenReturn(Optional.of(mapping));

        String result = urlService.resolveUrl("abc1234");

        assertThat(result).isEqualTo("https://example.com");
        verify(repository).findByShortCode("abc1234");
    }
}
