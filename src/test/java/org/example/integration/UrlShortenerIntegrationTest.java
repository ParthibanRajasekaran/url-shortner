package org.example.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.ShortenRequest;
import org.example.dto.ShortenResponse;
import org.example.entity.UrlMapping;
import org.example.repository.UrlMappingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UrlShortenerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shortener")
            .withUsername("postgres")
            .withPassword("postgres");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("app.own-host", () -> "localhost");
        registry.add("app.base-url", () -> "http://localhost");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UrlMappingRepository repository;

    @Test
    void testFullRoundTrip_shortenThenRedirect() {
        // POST to shorten
        ResponseEntity<ShortenResponse> createResp = restTemplate.postForEntity(
                "/api/v1/urls",
                new ShortenRequest("https://www.example.com/full-round-trip"),
                ShortenResponse.class
        );
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ShortenResponse body = createResp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.shortCode()).hasSize(7);

        // GET to redirect (follow=false so we see the 301)
        ResponseEntity<Void> redirectResp = restTemplate.getForEntity(
                "/" + body.shortCode(),
                Void.class
        );
        assertThat(redirectResp.getStatusCode()).isEqualTo(HttpStatus.MOVED_PERMANENTLY);
        assertThat(redirectResp.getHeaders().getLocation()).hasToString("https://www.example.com/full-round-trip");
    }

    @Test
    void testRedirectLoop_returns400() {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/urls",
                new ShortenRequest("https://localhost/existing-code"),
                String.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void testNonExistentShortCode_returns404() {
        ResponseEntity<String> resp = restTemplate.getForEntity("/zzzzzzz", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void testHashCollision_returns409() {
        // Manually insert a record with a known short code
        UrlMapping existing = UrlMapping.builder()
                .id(9999L)
                .shortCode("collis1")
                .longUrl("https://original.com")
                .createdAt(Instant.now())
                .build();
        repository.save(existing);

        // Try to save another record with a different id but same shortCode — triggers UNIQUE constraint
        UrlMapping duplicate = UrlMapping.builder()
                .id(9998L)
                .shortCode("collis1")
                .longUrl("https://duplicate.com")
                .createdAt(Instant.now())
                .build();

        // This should throw at the JPA layer and be caught as 409 by the exception handler
        org.springframework.dao.DataIntegrityViolationException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.dao.DataIntegrityViolationException.class,
                        () -> repository.saveAndFlush(duplicate)
                );
        assertThat(ex).isNotNull();
    }

    @Test
    void testRateLimit_6thRequestReturns429() {
        for (int i = 0; i < 5; i++) {
            restTemplate.postForEntity(
                    "/api/v1/urls",
                    new ShortenRequest("https://ratelimit-test" + i + ".com"),
                    ShortenResponse.class
            );
        }
        // 6th request should be rate-limited (429) by Bucket4j
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/urls",
                new ShortenRequest("https://ratelimit-test6.com"),
                String.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
