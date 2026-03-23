<div align="center">

# ⚡ SwiftLink

### A production-grade URL shortener engineered for scale — not a tutorial project.

[![CI](https://github.com/ParthibanRajasekaran/url-shortner/actions/workflows/ci.yml/badge.svg)](https://github.com/ParthibanRajasekaran/url-shortner/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.4-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791?logo=postgresql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white)
![NGINX](https://img.shields.io/badge/NGINX-1.25-009639?logo=nginx&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-blue)

</div>

---

Most URL shorteners are demo projects with a database and a random string. SwiftLink is built the way production systems actually need to work: distributed ID generation that survives multi-instance deployments, multi-layer caching that degrades gracefully when Redis goes down, rate limiting at both the gateway and application layers, and schema migrations you can roll back. Every design decision in this codebase has a reason — and that reason is documented here.

---

## Architecture

```
                ┌─────────────────────────────────────────┐
                │           Client Request                 │
                └──────────────┬──────────────────────────┘
                               │
                               ▼
               ┌───────────────────────────┐
               │       NGINX Gateway        │  ← Rate limiting (10 req/min per IP)
               │       (Port 80)            │  ← Load balancing (round-robin)
               │       nginx:1.25-alpine    │  ← proxy_redirect off (preserves 301s)
               └──────┬──────┬──────┬──────┘
                      │      │      │
           ┌──────────▼─┐ ┌──▼──────▼─┐ ┌──────────┐
           │  API Pod 1  │ │  API Pod 2  │ │  API Pod 3│
           │  Port 8080  │ │  Port 8080  │ │  Port 8080│
           │  Worker: 0  │ │  Worker: 1  │ │  Worker: 2│
           └──────┬──────┘ └──────┬──────┘ └────┬─────┘
                  │               │              │
                  └───────────────┼──────────────┘
                                  │
                    ┌─────────────┼─────────────┐
                    │             │              │
                    ▼             ▼              ▼
           ┌─────────────┐  ┌──────────┐  ┌──────────────┐
           │   Redis 7   │  │PostgreSQL│  │  Liquibase   │
           │ Cache-Aside │  │   16     │  │  Migrations  │
           │ 24h TTL     │  │shortener │  │  (schema     │
           │ 5min neg.   │  │   DB     │  │   owner)     │
           └─────────────┘  └──────────┘  └──────────────┘
```

**Request flow for `GET /{shortCode}`:**
```
NGINX → API Pod → [negative cache?] → [positive cache?] → PostgreSQL → repopulate Redis → 301
```
**Request flow for `POST /api/v1/urls`:**
```
NGINX (rate limit) → API Pod (Bucket4j rate limit) → Snowflake ID → Base62 → PostgreSQL → Redis → 201
```

---

## Why Each Decision Was Made

> The architecture is straightforward. The reasoning behind it is what matters.

### 1. Snowflake IDs, not UUIDs or auto-increment

Auto-increment integers leak business metrics (your competitor can tell how many URLs you've shortened). UUIDs are 128-bit, non-sequential, and perform poorly as B-tree index keys due to random insertion.

SwiftLink uses a **Twitter Snowflake-style distributed ID**: 64 bits, time-ordered, and worker-partitioned.

```
 63        22        12        0
  ┌─────────┬─────────┬─────────┐
  │timestamp│workerID │sequence │
  │ 41 bits │ 10 bits │ 12 bits │
  └─────────┴─────────┴─────────┘
```

- **41-bit timestamp** (delta from 2024-01-01 epoch) → IDs remain valid until ~2156
- **10-bit worker ID** → 1,024 concurrent instances without coordination
- **12-bit sequence** → 4,096 unique IDs per millisecond, per worker
- **Result**: ~4 million IDs/second at full cluster capacity, globally unique, time-sortable

> **Otherwise:** Auto-increment breaks under multi-instance deployments without a coordination layer. UUID v4 indexes fragment over time, causing write amplification that degrades PostgreSQL at scale.

---

### 2. Base62, not Base64

Base64 produces `+` and `/` characters, which are reserved in URLs. You'd need percent-encoding, which turns a short URL into a long one.

Base62 uses only `[a-zA-Z0-9]` — 62 characters that are universally URL-safe. A 7-character Base62 string covers **3.5 trillion unique codes** (62⁷ = 3,521,614,606,208). At 1,000 URLs/second continuously, that's 111 years of capacity.

```java
byte[] bytes = ByteBuffer.allocate(8).putLong(snowflakeId).array();
String shortCode = new String(Base62.createInstance().encode(bytes)).substring(0, 7);
```

> **Otherwise:** Base64 short codes require URL encoding in some clients, breaking copy-paste sharing and CDN cache keys.

---

### 3. 301 Permanent, not 302 Temporary

A 301 response tells browsers and CDNs to cache the redirect. After the first visit, a returning user is redirected entirely at the edge — the request never reaches your servers. For high-traffic links, this is the difference between your infrastructure scaling linearly with clicks vs. serving mostly cache hits.

A 302 would force every click through your stack, turning every viral link into a load event.

> **Otherwise:** 302 redirects disable browser caching. A link shared to a million people generates a million origin requests instead of one.

---

### 4. Two-layer caching with negative caching

Most implementations cache successful lookups. SwiftLink also caches **failures**.

```
Request for /abc1234
    ↓
[negative cache hit? → 404 immediately]   ← 300s TTL
    ↓
[positive cache hit? → 301 immediately]   ← 24h TTL
    ↓
[DB query → repopulate positive cache]
    ↓
[DB miss → write negative cache → 404]
```

The negative cache (key: `404:{shortCode}`, TTL: 5 minutes) prevents **cache penetration attacks** — a pattern where an adversary floods requests for random non-existent codes, bypassing cache and hammering the database on every request.

> **Otherwise:** Without negative caching, a script sending 10,000 requests/second for random codes (none of which exist in Redis) creates 10,000 DB queries/second. This is a common DDoS vector against caching architectures.

---

### 5. Redis resilience by design

Every Redis operation is wrapped in a try-catch. Cache failures are logged and ignored, not propagated.

```java
private String getFromCache(String shortCode) {
    try {
        return redissonClient.getBucket("url:" + shortCode).get();
    } catch (Exception e) {
        log.warn("Redis read failed for shortCode={}: {}", shortCode, e.getMessage());
        return null;  // fall through to DB
    }
}
```

The service degrades gracefully to database-only mode when Redis is unavailable. Latency increases. The service stays up.

> **Otherwise:** A Redis timeout that throws an unhandled exception cascades into a service outage. Your availability SLA becomes bounded by Redis uptime, not your application's own resilience.

---

### 6. Liquibase owns the schema, Hibernate validates it

`ddl-auto: validate` means Hibernate will refuse to start if the schema doesn't match the entity definitions — but it will never silently alter your database. Liquibase migrations are versioned, reversible, and auditable.

```sql
--liquibase formatted sql
--changeset swiftlink:001
CREATE TABLE url_mapping (
    id         BIGINT PRIMARY KEY,
    short_code VARCHAR(10) UNIQUE NOT NULL,
    long_url   TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_short_code ON url_mapping(short_code);
--rollback DROP INDEX idx_short_code; DROP TABLE url_mapping;
```

Every migration ships with a rollback statement.

> **Otherwise:** `ddl-auto: update` in a production environment has silently dropped columns during refactors. It is not a deployment strategy.

---

### 7. Defense-in-depth rate limiting

Two independent rate limiting layers with different scopes:

| Layer | Technology | Limit | Scope |
|---|---|---|---|
| Gateway | NGINX `limit_req_zone` | 10 req/min per IP, burst 5 | Cluster-wide |
| Application | Bucket4j `ConcurrentHashMap<IP, Bucket>` | 5 req/min per IP | Per-instance |

NGINX uses `$binary_remote_addr` (4 bytes) instead of `$remote_addr` (up to 15 bytes) for the rate limit zone key — a 60%+ memory reduction in the shared zone under high cardinality IP traffic.

> **Otherwise:** A single rate limit layer can be circumvented by routing requests to different upstream nodes. Defense-in-depth ensures both the cluster boundary and the application boundary enforce limits independently.

---

## Tech Stack

| Layer | Technology | Version | Why |
|---|---|---|---|
| Runtime | Java | 21 | Virtual threads, record types, pattern matching |
| Framework | Spring Boot | 3.3.4 | Auto-configuration, production-ready defaults |
| Database | PostgreSQL | 16 | ACID guarantees, B-tree indexes, TEXT type |
| Cache | Redis (Redisson) | 7 / 3.36.0 | Redisson chosen over Spring Data Redis for richer failover handling |
| ID Generation | Snowflake (custom) | — | Time-ordered, distributed, no coordination needed |
| Encoding | io.seruco:base62 | 0.1.3 | Lightweight, URL-safe, no `+/=` characters |
| Rate Limiting | Bucket4j | 8.10.1 | Token bucket algorithm, zero-dependency |
| Schema Mgmt | Liquibase | (managed) | Versioned, reversible DDL migrations |
| Gateway | NGINX | 1.25-alpine | Rate limiting, load balancing, TLS termination |
| Containers | Docker Compose | v3.9 | Local parity with production topology |
| Testing | JUnit 5 + Testcontainers | 1.20.1 | Real PostgreSQL + Redis in tests, no mocks for infrastructure |

---

## Quick Start

**Prerequisites:** Docker Desktop, Java 21+

```bash
# Clone
git clone https://github.com/ParthibanRajasekaran/url-shortner.git
cd url-shortner

# Start the full stack (NGINX + 3 API pods + PostgreSQL + Redis)
docker compose up --build

# Shorten a URL
curl -X POST http://localhost/api/v1/urls \
  -H "Content-Type: application/json" \
  -d '{"longUrl": "https://your-very-long-url.com/path?query=value"}'

# Follow the redirect
curl -Lv http://localhost/{shortCode}
```

**Run unit tests locally** (no Docker required):
```bash
# macOS/Linux — requires JDK 21+
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

./gradlew test \
  --tests "org.example.util.*" \
  --tests "org.example.service.*" \
  --tests "org.example.controller.*"
```

**Run integration tests** (requires Docker):
```bash
./gradlew test --tests "org.example.integration.*"
```

---

## API Reference

### `POST /api/v1/urls` — Shorten a URL

**Rate limited:** 5 requests/minute per IP (Bucket4j) + 10/minute at NGINX gateway

```bash
curl -X POST http://localhost/api/v1/urls \
  -H "Content-Type: application/json" \
  -d '{"longUrl": "https://example.com"}'
```

```json
// 201 Created
{
  "shortCode": "Ll7ojIV",
  "shortUrl":  "http://localhost/Ll7ojIV",
  "longUrl":   "https://example.com",
  "createdAt": "2026-03-23T20:29:23.986940Z"
}
```

| Condition | Status |
|---|---|
| `longUrl` blank or missing | `422 Unprocessable Entity` |
| `longUrl` doesn't start with `http(s)://` | `422 Unprocessable Entity` |
| URL points to this service (redirect loop) | `400 Bad Request` |
| Short code collision (astronomically rare) | `409 Conflict` |
| Rate limit exceeded | `429 Too Many Requests` |

---

### `GET /{shortCode}` — Redirect

Short codes are `[a-zA-Z0-9]{1,7}`. Anything else returns 404 immediately without a cache or DB lookup.

```bash
curl -v http://localhost/Ll7ojIV
# HTTP/1.1 301 Moved Permanently
# Location: https://example.com
```

| Condition | Status |
|---|---|
| Code found (cache or DB) | `301 Moved Permanently` + `Location` |
| Code not found | `404 Not Found` |
| Invalid format | `404 Not Found` (no DB hit) |

---

## Configuration

All settings have local defaults that work without Docker. Docker Compose overrides them via environment variables.

| Property | Default | Compose Override |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/shortener` | `jdbc:postgresql://postgres-db:5432/shortener` |
| `spring.data.redis.host` | `localhost` | `redis-cache` |
| `app.base-url` | `http://localhost:8080` | `http://localhost` |
| `app.own-host` | `localhost` | `localhost` |
| `app.snowflake.worker-id` | `0` | Set by `docker-entrypoint.sh` from hostname |
| `app.redis.url-ttl-seconds` | `86400` (24h) | — |
| `app.redis.not-found-ttl-seconds` | `300` (5min) | — |

**Snowflake worker ID per replica**: The entrypoint script extracts a number from the container hostname and applies `% 1024` to guarantee it stays in the valid range — no manual configuration needed per replica.

---

## Test Strategy

```
├── unit/
│   ├── SnowflakeIdGeneratorTest   — 10,000 sequential IDs, 10k concurrent (10 threads × 1k), monotonicity
│   ├── UrlServiceTest             — cache hit/miss, DB fallback, collision handling, redirect loop detection
│   ├── CachePenetrationTest       — DB hit once on repeated 404s, Redis-down graceful fallback
│   └── UrlControllerTest          — @WebMvcTest: request validation, 301 Location header, rate limit 429
│
└── integration/
    └── UrlShortenerIntegrationTest — Testcontainers (real PostgreSQL 16 + Redis 7)
        ├── Full shorten → redirect round-trip
        ├── Redirect loop → 400
        ├── Collision handling → 409
        ├── Negative cache verified in Redis
        └── Rate limit: 5 succeed, 6th → 429
```

> Integration tests use **real** PostgreSQL and Redis containers, not H2 or mock Redis. Infrastructure bugs that in-memory databases hide (migration failures, constraint violations, Redis key TTLs) are caught before they reach production.

---

## CI Pipeline

GitHub Actions runs on every push, every pull request to `main`, and **on a schedule every ~20 days** (1st and 21st of each month).

```
push / PR / schedule
        │
        ▼
    ┌───────┐
    │ Build │  — ./gradlew compileJava (fast gate, blocks test jobs on failure)
    └───┬───┘
        │
   ┌────┴────┐
   │         │
   ▼         ▼
┌──────┐  ┌───────────┐
│ Unit │  │Integration│  — Run in parallel
│Tests │  │  Tests    │
└──────┘  └───────────┘
```

Test reports are uploaded as artifacts on failure for immediate diagnosis.

---

## Production Considerations

This repo is a learning and reference implementation. Before taking it to production:

- **Worker ID assignment**: `docker-entrypoint.sh` derives the worker ID from container hostname. In Kubernetes, use a StatefulSet — the pod ordinal becomes the worker ID directly, eliminating any hostname parsing.
- **Rate limit state**: The Bucket4j `ConcurrentHashMap` is in-process and doesn't survive pod restarts. For persistent per-IP rate limiting across restarts, replace with a Redis-backed `ProxyManager` (the `bucket4j-redis` module). NGINX provides the true cluster-wide gate in the meantime.
- **TLS**: NGINX is configured for HTTP/80. Add a `ssl_certificate` block and redirect all HTTP to HTTPS.
- **Secret management**: Credentials are environment variables in Compose. In production, use Kubernetes Secrets, HashiCorp Vault, or AWS Secrets Manager.
- **Observability**: Spring Actuator exposes `/actuator/health`, `/actuator/metrics`. Wire Prometheus scraping from `/actuator/prometheus` and add a Grafana dashboard for `http_server_requests_seconds` and `jvm_memory_used_bytes`.

---

## Project Structure

```
.
├── src/main/java/org/example/
│   ├── SwiftLinkApplication.java       — Entry point
│   ├── config/
│   │   ├── AppProperties.java          — @ConfigurationProperties (base URL, TTLs, worker ID)
│   │   └── RateLimitConfig.java        — Bucket4j token bucket factory
│   ├── controller/UrlController.java   — POST /api/v1/urls, GET /{shortCode}
│   ├── service/UrlService.java         — Core logic: shorten, resolve, cache, fallback
│   ├── util/SnowflakeIdGenerator.java  — Thread-safe 64-bit distributed ID generator
│   ├── entity/UrlMapping.java          — JPA entity (Snowflake PK, no @GeneratedValue)
│   ├── repository/                     — Spring Data JPA (findByShortCode)
│   ├── dto/                            — ShortenRequest (validated), ShortenResponse
│   └── exception/                      — GlobalExceptionHandler, RedirectLoopException
├── src/main/resources/
│   ├── application.yml
│   └── db/changelog/                   — Liquibase master + 001-create-url-mapping.sql
├── src/test/                           — Unit + Integration test suites
├── Dockerfile                          — Multi-stage: JDK 21 builder → JRE 21 runtime
├── docker-entrypoint.sh                — Derives Snowflake worker ID from container hostname
├── docker-compose.yml                  — Full stack: NGINX + 3 API pods + PostgreSQL + Redis
├── nginx.conf                          — Rate limiting, load balancing, proxy_redirect off
└── .github/workflows/ci.yml           — Build gate + unit tests + integration tests
```

---

## License

MIT — use it, learn from it, build on it.
