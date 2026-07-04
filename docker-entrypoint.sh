#!/bin/sh
# Render (and most PaaS) provide config only as env vars — no file mounts. Aiven's Redis and Kafka
# clients need to trust the project's self-signed CA, so we materialize it from $AIVEN_CA_CERT (a PEM,
# possibly multi-line) to a file the app can point a truststore at. Skipped when the var is unset
# (e.g. local/default profile), so the image still runs against the plaintext compose stack.
set -e

CA_PATH="${AIVEN_CA_PATH:-/tmp/aiven-ca.pem}"
if [ -n "$AIVEN_CA_CERT" ]; then
  printf '%s\n' "$AIVEN_CA_CERT" > "$CA_PATH"
fi

# exec so the JVM is PID 1 and receives SIGTERM directly for graceful shutdown.
exec java $JAVA_OPTS -jar /app/app.jar
