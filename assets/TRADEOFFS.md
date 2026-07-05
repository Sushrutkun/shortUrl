# TRADEOFFS.md — URL Shortener

A companion to DESIGN.md that makes each key decision explicit: what was chosen, what was rejected, and why.

---

## 1. Redirect status: 302 vs 301

| Choice | Why |
|---|---|
| **302 Temporary (chosen)** | Forces every click through the redirect handler — analytics stay accurate. `Cache-Control: no-store` is also set as a second guard. |
| 301 Permanent (rejected) | Browsers and CDNs cache 301s permanently. After the first visit, subsequent clicks never reach the server, so click events are silently lost. |

---

## 2. Duplicate long-URL policy: return existing vs. mint new

| Choice | Why |
|---|---|
| **Return existing active code (chosen)** | Avoids proliferating dead codes for the same destination. Looked up via an indexed `url_hash` column (SHA-256 of the URL) — never a full-text scan of the 2048-char `original_url`. |
| Mint a new code each time (rejected) | Simpler insert path, but wastes codes and means the same destination ends up with many short URLs that all need to be tracked. |

---

## 3. Expiry enforcement: lazy check vs. active cleanup

| Choice | Why |
|---|---|
| **Lazy check on read (chosen)** | Zero scheduled-job complexity. Every `GET /{code}` compares `expires_at` to now before serving. An expired row is harmless to leave in place — it costs no storage worth worrying about at this scale. |
| Active cleanup job (rejected) | Requires a `@Scheduled` task, transactional bulk deletes, and care around race conditions (job deletes a row a request is about to read). Worth adding once dead-row volume becomes measurable, but overkill now — documented in DESIGN.md under "What I'd do next". |

---

## 4. Click counting: atomic SQL UPDATE vs. read-modify-write

| Choice | Why |
|---|---|
| **Atomic `UPDATE clicks = clicks + N` (chosen)** | MySQL (and H2) apply this as a single row-level operation. N concurrent updates to the same row all land correctly — no lost increments regardless of thread count. Proven by `ClickCountConcurrencyTest` (50 threads × 100 increments). |
| Read-modify-write in Java (rejected) | Classic race condition: thread A reads `clicks=5`, thread B reads `clicks=5`, both write `6`. One increment is lost. Even with `@Transactional`, the default isolation level (READ COMMITTED) does not protect against this. |

---

## 5. Click pipeline: inline DB write vs. Kafka async

| Choice | Why |
|---|---|
| **Kafka async pipeline (chosen)** | The redirect hot path is never blocked on analytics. A fire-and-forget Kafka publish adds microseconds; an inline UPDATE adds a DB round-trip on every redirect. Clicks are buffered in a `LongAdder` per code and flushed to the DB atomically every 60 seconds — one UPDATE per code per minute instead of one per click. |
| Inline DB write on every redirect (rejected) | Simpler, but couples redirect latency to DB write latency. Under high load (10k redirects/sec against one hot link), the DB becomes the bottleneck. |
| **Accepted tradeoff** | Stats can lag by up to ~60s. This is called out with `"approximate": true` in the stats response. One permanently-failed click event (Kafka DLT) = a one-click undercount — logged, never silently dropped. |

---

## 6. Rate limiting: sliding window vs. fixed window vs. token bucket

| Choice | Why |
|---|---|
| **Sliding window log via Redis sorted set (chosen)** | Exact count within any rolling 1-second window. No burst allowed at window boundaries (unlike fixed window). Implemented as a Redis sorted set per client IP: score = timestamp ms; trim old entries, count, reject or admit. |
| Fixed window (rejected) | Allows 2× the limit in a short burst straddling a window boundary (e.g., 8 requests in the last 10ms of second N and 8 more in the first 10ms of second N+1). |
| Token bucket (rejected) | Better for burst allowance (useful for APIs that want to permit short bursts), but adds state (token refill logic). Sliding window is simpler and sufficient for throttling the shorten endpoint. |

---

## 7. Code generation: SHA-256+UUID vs. sequential ID vs. random

| Choice | Why |
|---|---|
| **`base62(SHA-256(url + freshUUID))`[:8] (chosen)** | Not guessable (UUID mixed in), not sequential (no enumeration attack), reasonably short (8 chars = 62^8 ≈ 218 trillion codes). Bloom filter pre-checks avoid the DB on the fast path. |
| Sequential auto-increment base62 (rejected) | Trivially enumerable — anyone can scrape every URL in the system by walking `0, 1, 2, 3, ...`. |
| Pure random base62 (rejected) | Fine, but re-hashing on collision means the retry uses the same entropy pool (same URL). Mixing in a fresh UUID on each retry guarantees a genuinely different input, not just a lucky hash distribution. |

---

## 8. Bloom filter: in-memory (Guava) vs. Redis-backed

| Choice | Why |
|---|---|
| **In-memory Guava Bloom filter (chosen)** | Zero network overhead on the code-generation fast path. Appropriate for a single-instance deployment. |
| Redis-backed Bloom filter (rejected for now) | Required in a multi-instance deployment — each instance has its own filter, so an alias accepted by instance A might be unknown to instance B's filter. The DB check is always the source of truth; the filter is only a pre-check. Moving to a Redis bitfield would make it shared. Documented as "what I'd do next". |
| **Accepted limitation** | Soft-deleted codes are never removed from the Bloom filter (Guava's filter doesn't support deletion). A deleted alias is permanently retired rather than reusable. A counting Bloom filter would solve this. |

---

## 9. Cache TTL: fixed 5 minutes vs. link-aware

| Choice | Why |
|---|---|
| **Link-aware TTL (chosen)** | Redis TTL is capped to `min(5min, remainingLinkLifetime)`. A link with `ttl=PT30S` won't be served from cache for 5 minutes after it expires. Without this, lazy expiry would be bypassed on cache hits. |
| Fixed 5-minute TTL (rejected) | Simple but incorrect for short-lived links — a link with a 10-second TTL could redirect for 5 minutes after expiry. |

---

## 10. Analytics storage: same table vs. separate table

| Choice | Why |
|---|---|
| **Clicks column on `short_urls` (chosen)** | Zero joins on `GET /stats` — one row lookup returns code metadata and click count together. Simple schema, and the Kafka flush batches 60 seconds of clicks into one `UPDATE` per code (~1.7 writes/sec at 100 active codes), so write contention is negligible at this scale. |
| Separate `click_counts` table (rejected for now) | The right design at scale. The `short_urls` table has two conflicting write patterns: codes are written once and then read-heavy; clicks are written every 60 seconds per active link. Under high load (10k redirects/sec across many hot links), the flush job's `UPDATE`s would contend with the redirect path's reads on the same rows, causing InnoDB row-lock wait timeouts. Separating them decouples the redirect hot path from the analytics write path entirely. |
| Time-series store (ClickHouse / TimescaleDB) | The right design for richer analytics (clicks over time, referrer, user-agent breakdown). Out of scope for this exercise but the Kafka pipeline already produces the raw events needed to feed one. |
| **What I'd do next** | Split into `short_urls` (immutable after creation) and `click_counts` (short_code, clicks, last_flushed_at) once redirect throughput makes lock contention measurable. |

---

## 11. Short code mutability: immutable after creation

| Choice | Why |
|---|---|
| **Code is immutable once created (chosen)** | `PUT /api/v1/urls/{code}` only updates the destination URL and TTL — the `alias` field in the request body is intentionally ignored. Changing a code in-place touches four systems atomically: the DB row, the Bloom filter, the Redis cache key, and the click-count history. Getting all four right without a window of inconsistency is a disproportionate complexity increase for a rarely-needed operation. |
| Allow code rename via PUT (rejected) | Would require: soft-deleting the old code and inserting a new row (losing click history), or updating `short_code` in-place and evicting the *old* Redis key (not the new one). Either way, the old code can never be freed from the Bloom filter (Guava doesn't support deletion), permanently retiring it. The benefit — letting a caller rename a vanity alias — does not justify this surface area. |
| **Accepted limitation** | A caller who wants a different code must DELETE the old one and POST a new one. Click history does not carry over. This is documented behaviour, not a silent constraint. |
