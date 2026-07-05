# DESIGN.md — URL Shortener

## Data model

Single table, `short_urls`:

| Column        | Type          | Notes                                                            |
|---------------|---------------|-------------------------------------------------------------------|
| id            | BIGINT PK     | auto-increment                                                    |
| short_code    | VARCHAR(16)   | **UNIQUE + indexed** — the hottest lookup path (`GET /{code}`) needs O(1)/O(log n) access, not a table scan. |
| original_url  | VARCHAR(2048) | the long URL                                                      |
| url_hash      | VARCHAR(64)   | **indexed**, SHA-256 hex digest of `original_url`. Duplicate-URL detection (same long URL submitted twice) is a lookup by hash, never a scan of the raw 2048-char `original_url` column. This trades a small amount of extra write overhead (one more index to maintain per insert) for avoiding a full-text/prefix scan on every POST. |
| custom_alias  | BOOLEAN       | true if the code was caller-supplied rather than generated        |
| clicks        | BIGINT        | updated only via atomic `UPDATE clicks = clicks + N`               |
| created_at    | TIMESTAMP     |                                                                    |
| expires_at    | TIMESTAMP NULL| null = never expires                                              |
| deleted       | BOOLEAN       | soft-delete flag; `DELETE` never removes the row                  |

## Code generation strategy

Each code is `base62(SHA-256(originalUrl + freshUUID))`, truncated to 8 characters.

- **Not sequential auto-increment.** A plain counter (0,1,2,3…) is trivially enumerable — anyone could scrape every URL in the system by walking the counter. Hashing with a random UUID mixed in avoids this.
- **Collision handling:** before insert, check a Guava Bloom filter. If it says "definitely not taken," proceed immediately (fast path, no DB hit). If it says "maybe taken," fall through to a real DB existence check — the Bloom filter is never treated as a source of truth, only a pre-filter. On a genuine collision, retry with a **new UUID** (not just a new hash of the same input), up to 3 attempts, then fail with `5xx`.
- **Bloom filter limitation:** it supports add but not remove. A soft-deleted code/alias is never freed from the filter, so it's **permanently retired** rather than reusable. Documented tradeoff — a counting Bloom filter would solve this but is unnecessary complexity for this exercise.
- **Custom aliases** go through the same Bloom-filter-then-DB check; a genuine conflict returns `409`.
- **Scale note:** at ~100 shortens/sec (per the stretch-goal scale note), this scheme is fine. The in-memory Bloom filter is per-JVM — a multi-instance deployment would need it backed by a shared store (e.g. Redis bitfield) instead.

## Duplicate long-URL policy

Resubmitting the same long URL returns the **existing active code** rather than minting a new one (looked up via `url_hash`, see above). Chosen because it avoids proliferating dead codes for the same destination; the tradeoff is the extra indexed column.

## Concurrency (click counting)

The classic trap is a read-then-write (`count = read(); count++; write(count)`), which loses updates under concurrent redirects to the same code.

Two layers, both avoiding read-modify-write entirely:

1. **Ingestion:** each redirect fires a Kafka event (no partition key) rather than writing to the DB inline — the redirect response is never blocked on analytics.
2. **Aggregation:** a Kafka consumer buffers events in memory per code using `LongAdder` (lock-free, thread-safe). Every 60 seconds a scheduled flush issues one **atomic** `UPDATE short_urls SET clicks = clicks + N WHERE short_code = ?` per code — a single row-level SQL operation, so MySQL applies it correctly regardless of how many increments were batched or how many threads contributed them.

**Tradeoff:** `GET /stats` can lag real click activity by up to the 60s flush interval. Traded deliberately: one UPDATE per code per minute instead of one UPDATE per click, at any load. This is called out as `"approximate": true` in the stats response.

**Kafka reliability:** producer uses `acks=all` with retries. Consumer uses 3 attempts with backoff (`@RetryableTopic`, auto-provisioning 2 retry topics), falling through to a dead-letter topic on final failure so one bad message never blocks a partition. A permanently-failed click event only means a one-click undercount — logged, never silently dropped without a trace.

## Rate limiting

Lives as **middleware inside this single service** (a servlet filter on `POST /api/v1/urls`), not at an external gateway — matches the "build a single service" constraint. The same logic would move to a shared gateway in a multi-service deployment without changing the algorithm.

**Algorithm:** sliding window log, implemented with a Redis sorted set per client key (IP, or `X-Forwarded-For` if present): score = request timestamp; trim entries older than the window on each request, then count what's left. Limit set to roughly 80% of measured per-IP throughput headroom — tight enough to blunt a single-IP DDoS, loose enough not to bother normal traffic.

## API deviation: `ttlDays` → `ttl`

The spec defines `"ttlDays": 30` (integer, whole days only). This service accepts `"ttl"` as an ISO-8601 duration string instead (e.g. `"P30D"`, `"PT1H"`, `"PT30S"`). This is a strict superset — `"P30D"` is equivalent to `"ttlDays": 30` — and allows finer-grained expiry without changing any other behaviour.

## Redirect status code

**302, not 301.** Browsers and CDNs cache 301s — after the first hit, repeat clicks never reach the server again, so click events are silently lost. 302 forces every click through the redirect handler, keeping analytics accurate. `Cache-Control: no-store` is set explicitly on the response as a second guard against caching.

## Expiry

**Lazy check on read**, not an active cleanup job: on every `GET /{code}`, compare `expires_at` to now before serving. Simpler than a scheduler at this scale, and an expired row costs nothing to leave in place until it's naturally read again (or never). Documented as a "what I'd add next" if link volume grew large enough that dead rows became a storage concern.

## What was deliberately left out

- Active/scheduled expiry cleanup (lazy check only).
- Cross-instance Bloom filter sharing (fine for single-instance; would move to Redis-backed at multi-instance scale).
- Alias reuse after soft-delete (see Bloom filter limitation above).
- Referrer/user-agent breakdown in analytics (stats endpoint returns count + timestamps only).
- API-key-based rate limiting (IP-based only for this exercise).

## What I'd do next with more time

- Add a counting Bloom filter (or periodic filter rebuild from DB) to allow alias reuse after deletion.
- Add an active expiry-cleanup job once dead-row volume justifies it.
- Extend analytics with referrer/user-agent dimensions.
- Move rate limiting to a shared API gateway if this service is decomposed further.
