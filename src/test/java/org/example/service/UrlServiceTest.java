package org.example.service;

import org.example.config.AppProperties;
import org.example.dto.ShortenRequest;
import org.example.dto.ShortenResponse;
import org.example.entity.UrlMapping;
import org.example.exception.RedirectLoopException;
import org.example.repository.UrlMappingRepository;
import org.example.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

    @Mock
    private UrlMappingRepository repository;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private AppProperties appProperties;
    private UrlService urlService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setOwnHost("swiftlink.io");
        appProperties.setBaseUrl("https://swiftlink.io");
        urlService = new UrlService(repository, redissonClient, snowflakeIdGenerator, appProperties);
    }

    @Test
    void testShortenUrl_success_shortCodeIs7Chars() {
        when(snowflakeIdGenerator.nextId()).thenReturn(123456789012345L);
        RBucket rBucket = mock(RBucket.class);
        doReturn(rBucket).when(redissonClient).getBucket(anyString());

        UrlMapping saved = UrlMapping.builder()
                .id(123456789012345L).shortCode("abcdefg")
                .longUrl("https://example.com").createdAt(Instant.now()).build();
        when(repository.save(any())).thenReturn(saved);

        ShortenResponse response = urlService.shortenUrl(new ShortenRequest("https://example.com"));

        assertThat(response.shortCode()).hasSize(7);
        assertThat(response.shortCode()).matches(Pattern.compile("[a-zA-Z0-9]{7}"));
        assertThat(response.longUrl()).isEqualTo("https://example.com");
    }

    @Test
    void testShortenUrl_redirectLoop_throwsException() {
        assertThatThrownBy(() ->
                urlService.shortenUrl(new ShortenRequest("https://swiftlink.io/abc123"))
        ).isInstanceOf(RedirectLoopException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void testShortenUrl_dbCollision_propagatesException() {
        when(snowflakeIdGenerator.nextId()).thenReturn(999L);
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() ->
                urlService.shortenUrl(new ShortenRequest("https://example.com"))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void testResolveUrl_cacheHit_noDatabaseCall() {
        RBucket negBucket = mock(RBucket.class);
        RBucket posBucket = mock(RBucket.class);
        doReturn(negBucket).when(redissonClient).getBucket("404:abc1234");
        doReturn(posBucket).when(redissonClient).getBucket("url:abc1234");
        when(negBucket.isExists()).thenReturn(false);
        when(posBucket.get()).thenReturn("https://example.com");

        String result = urlService.resolveUrl("abc1234");

        assertThat(result).isEqualTo("https://example.com");
        verify(repository, never()).findByShortCode(anyString());
    }

    @Test
    void testResolveUrl_cacheMiss_dbHit_repopulatesCache() {
        RBucket negBucket = mock(RBucket.class);
        RBucket posBucket = mock(RBucket.class);
        doReturn(negBucket).when(redissonClient).getBucket("404:abc1234");
        doReturn(posBucket).when(redissonClient).getBucket("url:abc1234");
        when(negBucket.isExists()).thenReturn(false);
        when(posBucket.get()).thenReturn(null);

        UrlMapping mapping = UrlMapping.builder()
                .id(1L).shortCode("abc1234").longUrl("https://example.com").createdAt(Instant.now()).build();
        when(repository.findByShortCode("abc1234")).thenReturn(Optional.of(mapping));

        String result = urlService.resolveUrl("abc1234");

        assertThat(result).isEqualTo("https://example.com");
        verify(posBucket).set(anyString(), anyLong(), any());
    }

    @Test
    void testResolveUrl_notFound_writesNegativeCache() {
        RBucket negBucket = mock(RBucket.class);
        RBucket posBucket = mock(RBucket.class);
        doReturn(negBucket).when(redissonClient).getBucket("404:abc1234");
        doReturn(posBucket).when(redissonClient).getBucket("url:abc1234");
        when(negBucket.isExists()).thenReturn(false);
        when(posBucket.get()).thenReturn(null);
        when(repository.findByShortCode("abc1234")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.resolveUrl("abc1234"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));

        verify(negBucket, times(1)).set(any(), anyLong(), any());
    }

    @Test
    void testResolveUrl_invalidShortCode_returns404WithoutDbOrRedisCall() {
        assertThatThrownBy(() -> urlService.resolveUrl("!invalid"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));

        verify(repository, never()).findByShortCode(anyString());
        verify(redissonClient, never()).getBucket(anyString());
    }
}
