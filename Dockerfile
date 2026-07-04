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

# Copy just the built jar from the build stage (wildcard tolerates the version in the name).
COPY --from=build /app/target/url-shortener-*.jar app.jar
USER app

EXPOSE 8080

# `exec` so the JVM is PID 1 and receives SIGTERM directly for graceful shutdown.
# JAVA_OPTS is passed through for per-environment tuning (heap, GC, -Dspring.profiles.active=...).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
