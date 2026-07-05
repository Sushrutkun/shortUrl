# ---- Build stage ----
# Compile and package the app with Maven. Kept separate from the runtime image so build
# tooling (JDK, Maven, ~/.m2 cache) never ships to production.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Resolve dependencies first, in their own layer, so edits to src/ don't re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage ----
# JRE-only base: smaller surface than the full JDK.
FROM eclipse-temurin:17-jre
WORKDIR /app

# Run as a non-root user.
RUN groupadd --system app && useradd --system --gid app app

# Container-aware JVM: size the heap to 75% of the container's memory limit (cgroup) instead of
# the JDK default 25%. Override at runtime, e.g. -e JAVA_OPTS="-XX:MaxRAMPercentage=50.0 -Xss512k".
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# Copy the built jar, owned by the runtime user (wildcard tolerates the version in the name).
COPY --from=build --chown=app:app /app/target/url-shortener-*.jar app.jar

# Entrypoint sources a mounted /app/.env (if present), materializes the Aiven CA, then launches
# the JVM. See the script header. Supply local config with: -v "$PWD/.env:/app/.env:ro".
# --chmod makes it executable in the same layer (no extra RUN chmod).
COPY --chown=app:app --chmod=0755 docker-entrypoint.sh /app/docker-entrypoint.sh

USER app

EXPOSE 8080

# JAVA_OPTS is passed through for per-environment tuning (heap, GC, -Dspring.profiles.active=...).
ENTRYPOINT ["/app/docker-entrypoint.sh"]
