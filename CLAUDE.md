# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project: SwiftLink URL Shortener

A high-throughput URL shortening service built with Spring Boot 3.x / Java 21. The repo is currently a plain Gradle skeleton; the full service is being built per the spec below.

## Build & Run Commands

```bash
# Build (skip tests)
./gradlew build -x test

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "org.example.SomeServiceTest"

# Run the application locally
./gradlew bootRun

# Start all infrastructure (PostgreSQL, Redis, NGINX, app replicas)
docker compose up --build

# Scale app instances manually
docker compose up --scale swiftlink-api=3
```

## Intended Architecture

The project must be migrated from a plain Gradle Java project to Spring Boot 3.x. The `build.gradle.kts` and `settings.gradle.kts` will need to be updated accordingly.

### Request Flow

```
Client → NGINX (port 80) → swiftlink-api (3 replicas, port 8080) → Redis → PostgreSQL
```

- **POST /api/v1/urls** - shorten a URL: Snowflake ID → Base62 encode → write to DB + Redis
- **GET /{shortCode}** - redirect: Redis lookup → DB fallback → 301 response

### Key Components

| Layer | Technology | Notes |
|---|---|---|
| Web | Spring Boot 3.x REST | `UrlController` |
| ID Generation | Snowflake algorithm | Singleton bean; 64-bit (timestamp + worker ID + sequence) |
| Encoding | `io.seruco.encoding:base62` | Produces 7-char URL-safe strings from Snowflake IDs |
| Persistence | Spring Data JPA + PostgreSQL | `UrlMapping` entity; `id` is **not** auto-generated (set from Snowflake) |
| Cache | Redisson (not Spring Data Redis) | Cache-Aside pattern; Redis down → graceful DB fallback |
| Rate Limiting | Bucket4j + Redis | 5 POST requests/min per IP |
| Gateway | NGINX | Rate limiting, load balancing, SSL termination |
| Schema Mgmt | Liquibase or Flyway | Manages `url_mapping` table + `idx_short_code` index |

### Database Schema

```sql
CREATE TABLE url_mapping (
    id         BIGINT PRIMARY KEY,        -- Snowflake ID, not auto-generated
    short_code VARCHAR(10) UNIQUE,        -- Base62-encoded string
    long_url   TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_short_code ON url_mapping(short_code);
```

## Implementation Guardrails

- **Base62 only** - use `[a-zA-Z0-9]`; never Base64 (`+` and `/` break URLs).
- **301 not 302** - permanent redirects allow browser/CDN caching.
- **URL validation** - `longUrl` must start with `http://` or `https://`.
- **Short code validation** - must match `^[a-zA-Z0-9]{1,7}$`.
- **Redis resilience** - all Redis calls must be wrapped in try/catch; log failures with `@Slf4j` and fall through to DB. The service must never return 500 due to a Redis outage.
- **No redirect loops** - reject any `longUrl` that resolves to the service's own host.
- **Collision handling** - catch `DataIntegrityViolationException` on save; do not silently swallow it.
- **Negative caching** - cache 404 results in Redis (short TTL) to prevent DB hammering on non-existent codes.

## NGINX Configuration Notes

Rate limiting zone targets POST to `/api/v1/urls` (10 req/min per IP, burst=5). The upstream block (`shortener_api`) load-balances across `swiftlink-api-1`, `swiftlink-api-2`, `swiftlink-api-3` on port 8080. `proxy_redirect off` on the redirect endpoint prevents NGINX from rewriting 301 Location headers.

## Docker Compose Layout

- `postgres-db` - PostgreSQL on port 5443 (internal), database `shortener`
- `redis-cache` - Redis (internal only)
- `swiftlink-api` - Spring Boot app with `deploy.replicas: 3`
- `nginx-gateway` - exposes port 80, mounts `./nginx.conf`
- All services on `swiftlink-network` (bridge driver)

Spring Boot env vars injected via Compose:
```
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres-db:5443/shortener
SPRING_REDIS_HOST=redis-cache
```
