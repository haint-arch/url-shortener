# URL Shortener

A high-performance URL shortening service designed to handle billions of URLs. Built with Java/Spring Boot, Apache Cassandra, Redis, and Zookeeper.

### System Architecture

```
                         Clients
                            |
                      Load Balancer
                            |
              +-------------+-------------+
              |             |             |
          Server 1      Server 2      Server 3
          Range: 0-1M   Range: 1M-2M  Range: 2M-3M
              |             |             |
              +------+------+------+------+
                     |             |
                Redis Cache    Cassandra Cluster
              (hot URLs, TTL)  (source of truth)

              Zookeeper Cluster
              (range assignment for distributed ID generation)
```

### How It Works

**Creating a short URL (POST /shorten)**

1. App server gets next unique ID from its pre-assigned range (local counter, no network call)
2. Converts the numeric ID to a 7-character base62 code (`0-9`, `a-z`, `A-Z`)
3. Inserts mapping directly into Cassandra (no existence check needed — ranges guarantee uniqueness)
4. Caches the mapping in Redis (write-through, so first click is fast)
5. Returns the short URL

**Redirecting (GET /{code})**

1. Checks Redis cache first (~1ms)
2. On cache hit (99% of cases): returns HTTP 301 redirect immediately
3. On cache miss: queries Cassandra, caches the result, then redirects

### Tech Stack

| Component | Technology | Why |
|-----------|-----------|-----|
| API | Spring Boot 3, Java 17 | Production-grade framework |
| Storage | Apache Cassandra | Write-optimized, horizontally scalable, O(1) partition key lookup |
| Cache | Redis | Sub-millisecond reads for hot URLs, shared rate limit counters |
| ID Generation | Zookeeper + Base62 | Distributed counter ranges, zero-collision across servers |
| Containerization | Docker Compose | Local dev with all dependencies |

### Key Design Decisions

**Why Zookeeper for ID generation?**

Each server gets a unique range of numbers (e.g., server 1 gets 0-1M, server 2 gets 1M-2M). ID generation is a local atomic increment — no DB call, no network hop, no collision possible. Zookeeper is only contacted once per million IDs.

**Why Cassandra over PostgreSQL?**

URL shortener is write-heavy with simple key-value lookups. Cassandra writes are sequential (commit log + memtable), partition key lookups are O(1), and it scales horizontally by adding nodes. No single point of failure (masterless architecture).

**Why Redis cache?**

Redirects happen ~100x more than creation. Redis serves hot URLs in ~1ms vs ~5-10ms from Cassandra. If Redis goes down, the service falls back to Cassandra gracefully (fail-open design).

**Consistency levels**

- Write: QUORUM (2/3 nodes must confirm) — safe against single node failure
- Read: LOCAL_ONE (fastest) — safe because URL mappings are immutable after creation

**Rate limiting**

Redis-based fixed window counter shared across all app servers. 10 POST requests per IP per 60 seconds. GET redirects are not rate-limited (cheap, cached operations).

### API

**Create short URL**

```
POST /shorten
Content-Type: application/json

{"url": "https://example.com/very/long/path"}
```

Response (201 Created):

```json
{
  "shortUrl": "http://localhost:8080/0004c92",
  "originalUrl": "https://example.com/very/long/path",
  "code": "0004c92"
}
```

**Redirect**

```
GET /{code}

HTTP/1.1 301 Moved Permanently
Location: https://example.com/very/long/path
```

**Rate limit exceeded**

```
HTTP/1.1 429 Too Many Requests
Retry-After: 60
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 0

{"error": "Too Many Requests", "message": "Rate limit exceeded. Max 10 requests per 60s"}
```

### Running Locally

Prerequisites: Docker and Docker Compose.

```bash
docker compose up -d
```

This starts Cassandra, Redis, Zookeeper, and the app. Once all services are healthy, initialize the Cassandra schema:

```bash
docker exec -i url-shortener-cassandra-1 cqlsh -e "
  CREATE KEYSPACE IF NOT EXISTS url_shortener
    WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};
  USE url_shortener;
  CREATE TABLE IF NOT EXISTS url_mappings (
    code text PRIMARY KEY, id uuid, original_url text, created_at timestamp
  );"
```

Test it:

```bash
# Create a short URL
curl -X POST http://localhost:8080/shorten \
  -H "Content-Type: application/json" \
  -d '{"url": "https://github.com"}'

# Redirect (open in browser or use curl -L)
curl -L http://localhost:8080/{code}
```

### Project Structure

```
src/main/java/com/urlshortener/
  controller/    UrlController          POST /shorten, GET /{code}
  service/       UrlShortenerService    Core business logic (create + resolve)
                 RangeService           Zookeeper-based distributed ID counter
  model/         UrlMapping             Cassandra entity
  repository/    UrlMappingRepository   Cassandra queries (CL: QUORUM write, LOCAL_ONE read)
  config/        ZookeeperConfig        Curator client bean
                 RateLimitFilter        Redis-based IP rate limiting
  util/          Base62Encoder          Number <-> 7-char code conversion
  dto/           ShortenRequest/Response API data transfer objects
  exception/     GlobalExceptionHandler Centralized error handling

src/test/java/com/urlshortener/
  util/          Base62EncoderTest      Encode/decode correctness, edge cases
  service/       UrlShortenerServiceTest Cache hit/miss, graceful degradation
  controller/    UrlControllerTest      HTTP 201, 301, 400, 404 responses
```

### Capacity

Base62 with 7 characters: 62^7 = 3,521,614,606,208 possible codes (~3.5 trillion). With billions of URLs, less than 1% of the code space is used.
