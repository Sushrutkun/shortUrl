# URL Shortener

Production-minded URL shortener (Java 17, Spring Boot 3.2).

See **DESIGN.md** for the design note (data model, code generation, concurrency, rate limiting, tradeoffs).

## Stack

- Java 17, Spring Boot 3.2 (Web, Data JPA, Validation, Data Redis, Kafka)
- MySQL (persistence) — H2 for tests
- Redis (cache-aside for redirects, sliding-window rate-limit counters)
- Kafka (async click-event pipeline, with retry topics + DLT)
- Guava Bloom filter (fast pre-check on code/alias creation)

## Prerequisites

- JDK 17+
- Maven 3.8+
- MySQL running locally (or update `spring.datasource.url` in `application.yml`)
- Redis running locally on `6379`
- Kafka running locally on `9092`

Quickest way to get MySQL/Redis/Kafka up locally for manual testing:

```bash
docker run -d --name mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8
docker run -d --name redis -p 6379:6379 redis:7
docker run -d --name kafka -p 9092:9092 apache/kafka:3.7.0
```

## Build & run

```bash
mvn clean install
mvn spring-boot:run
```

Service starts on `http://localhost:8080`.

## Run tests

```bash
mvn test
```

Tests use an in-memory H2 database and an embedded Kafka broker (`@EmbeddedKafka`) — no external
services required to run the test suite. Redis calls are mocked in the integration test.

- `UrlServiceTest` — unit tests for shorten/redirect/update/delete business logic (Mockito).
- `CodeGeneratorServiceTest` — unit tests for code generation, Bloom-filter fallback, dedup hashing.
- `UrlControllerIntegrationTest` — MockMvc end-to-end tests against the real HTTP contract.
- `ClickCountConcurrencyTest` — proves the atomic click-increment path never loses counts under
  50 threads × 100 increments against the same row.

## API

See `DESIGN.md` and the original spec for the full contract. Summary:

```
POST   /api/v1/urls                  create a short URL
GET    /{code}                       302 redirect to the original URL
GET    /api/v1/urls/{code}/stats     click count + metadata (approximate, see DESIGN.md)
PUT    /api/v1/urls/{code}           update the mapping (evicts cache)
DELETE /api/v1/urls/{code}           soft delete (evicts cache)
```

## Example requests

```bash
curl -X POST http://localhost:8080/api/v1/urls \
  -H "Content-Type: application/json" \
  -d '{"url": "https://example.com/very/long/path", "alias": "promo", "ttlDays": 30}'

curl -i http://localhost:8080/promo   # 302 -> Location: https://example.com/very/long/path

curl http://localhost:8080/api/v1/urls/promo/stats
```
