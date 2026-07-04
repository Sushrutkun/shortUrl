# Deploy url-shortener to Render backed by Aiven (MySQL + Valkey/Redis + Kafka)

## Context

The service currently runs only against the local `docker-compose` stack (MySQL/Redis/Kafka on
plaintext localhost). We need to deploy the container to **Render** (Docker runtime) and point it at
managed **Aiven** services from `console.aiven.io` (free-trial tier).

Three things make this more than a hostname swap:
1. **All Aiven services require TLS.** Aiven signs its service certs with a per-project **self-signed
   CA**, so Redis and Kafka clients must trust that CA (MySQL can use encryption-only mode to skip it).
2. **Aiven Kafka needs real auth.** We'll use **SASL_SSL / SCRAM-SHA-256** (chosen). Requires enabling
   SASL in the Aiven Kafka service, then username + password + CA on the client.
3. **Render injects `$PORT`** and only supplies configuration via env vars (no file mounts), so secrets
   and the CA cert must arrive as env vars, and the app must bind `$PORT`.

Approach: add a dedicated **`prod` Spring profile** that wires TLS + reads every secret from env
placeholders, leave the local dev config (default profile) untouched, materialize the shared Aiven CA
from an env var at container start, and ship a `render.yaml` Blueprint plus env scaffolding.

## Changes

### 1. `src/main/resources/application.yml` (base profile — minimal)
- Bind Render's port: change `server.port: 8080` → `server.port: ${PORT:8080}`.
- Everything else in the base file stays as the local-dev default.

### 2. New `src/main/resources/application-prod.yml` (activated by `SPRING_PROFILES_ACTIVE=prod`)
Wire all three backends to Aiven over TLS, secrets via env:
- **MySQL**: `url: jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}?sslMode=REQUIRED`
  (`REQUIRED` = encrypted, no CA import needed). Use Aiven's existing `defaultdb`; **drop
  `createDatabaseIfNotExist`** (managed MySQL user can't create schemas that way).
  `username: ${MYSQL_USERNAME}`, `password: ${MYSQL_PASSWORD}`.
- **Redis (Valkey)**: `host/port/username(default)/password` from env; `ssl.enabled: true` +
  `ssl.bundle: aiven`. Define `spring.ssl.bundle.pem.aiven.truststore.certificate:
  ${AIVEN_CA_PATH:/tmp/aiven-ca.pem}` so Lettuce verifies against the Aiven CA. (Spring Boot 3.2.5
  supports `spring.data.redis.ssl.bundle` + PEM bundles.)
- **Kafka**: `bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}`, `security.protocol: SASL_SSL`, and
  under `spring.kafka.properties`:
  - `sasl.mechanism: SCRAM-SHA-256`
  - `sasl.jaas.config: org.apache.kafka.common.security.scram.ScramLoginModule required username="${KAFKA_USERNAME}" password="${KAFKA_PASSWORD}";`
  - `ssl.truststore.type: PEM`, `ssl.truststore.location: ${AIVEN_CA_PATH:/tmp/aiven-ca.pem}`
  - `ssl.endpoint.identification.algorithm: https`
  - Producer/consumer serializers are inherited from the base file (same keys).
- Set `app.base-url: ${APP_BASE_URL:http://localhost:8080}` so short links use the Render URL.

### 3. `src/main/java/.../config/KafkaTopicConfig.java`
Aiven multi-broker clusters reject `replicas(1)`. Make it configurable:
- Inject `@Value("${app.kafka.replication-factor:1}") short replicas` and pass to `.replicas(replicas)`.
- Set `app.kafka.replication-factor: 2` in `application-prod.yml` (matches Aiven startup plans; adjust
  to plan's broker count). Local default stays `1`.

### 4. `Dockerfile` — materialize the CA, keep non-root + graceful shutdown
- Add a small `docker-entrypoint.sh` (COPYed in, `chmod +x`) that writes `$AIVEN_CA_CERT` (PEM, may be
  multiline) to `${AIVEN_CA_PATH:-/tmp/aiven-ca.pem}` when set, then `exec java $JAVA_OPTS -jar
  /app/app.jar`. `/tmp` is writable by the non-root `app` user.
- Replace the current inline `ENTRYPOINT` with `ENTRYPOINT ["/app/docker-entrypoint.sh"]`.

### 5. `render.yaml` (new, repo root) — Render Blueprint
- `type: web`, `runtime: docker`, `plan: free`, `dockerfilePath: ./Dockerfile`.
- `healthCheckPath: /swagger-ui/index.html` (springdoc UI is already a dependency; no actuator in pom).
- `envVars`: `SPRING_PROFILES_ACTIVE=prod` (literal); all secrets (`MYSQL_*`, `REDIS_*`, `KAFKA_*`,
  `AIVEN_CA_CERT`, `APP_BASE_URL`) as `sync: false` so values are entered in the dashboard, never
  committed.

### 6. Env scaffolding
- **`.env.example`** (committed): every var name with placeholder/explanatory values + a comment block
  mapping each Aiven console field → env var. `.env*` is already gitignored, so no secrets leak.
- **`.env`** (gitignored) is *not* consumed by Render at runtime, but useful for `docker run` locally
  against Aiven; generated from the example with `CHANGE_ME` placeholders.

## Notes / caveats
- **Enable SASL** on the Aiven Kafka service (Service → Advanced/Authentication) and create the SCRAM
  user; mTLS is on by default but SASL is not.
- Kafka has **no permanent free plan** on Aiven — it runs on the $300/30-day trial credits (MySQL and
  Valkey do have free plans).
- The single Aiven **project CA** (`AIVEN_CA_CERT`) covers all three services; download it once from any
  service page (or Project → CA certificate).
- Topic auto-creation is off on Aiven; the `NewTopic` bean + `@RetryableTopic` create topics via
  AdminClient, which works provided the SCRAM user has topic-admin ACLs (default for `avnadmin`).

## Verification
1. **Build locally:** `mvn -q clean package -DskipTests` then `docker build -t url-shortener .`.
2. **Smoke test against Aiven from the workstation:** copy `.env.example` → `.env`, fill real Aiven
   values, then
   `docker run --rm --env-file .env -e SPRING_PROFILES_ACTIVE=prod -e AIVEN_CA_CERT="$(cat ca.pem)" -e PORT=8080 -p 8080:8080 url-shortener`.
   Watch logs for successful Hikari (MySQL), Lettuce (Redis), and Kafka consumer group connection — no
   TLS/handshake or auth errors.
3. **Functional check:** `POST /api/v1/urls` to create a short code, `GET /{code}` to resolve it
   (MySQL + Redis cache), and confirm a click event lands (Kafka).
4. **Deploy:** push branch, connect repo in Render (Blueprint picks up `render.yaml`), set the
   `sync:false` env vars in the dashboard, deploy, hit the same endpoints on the Render URL, and set
   `APP_BASE_URL` to the assigned `onrender.com` URL so returned short links are correct.
