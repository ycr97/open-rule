#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
: "${STUDIO_JDBC_URL:?Set the Studio JDBC URL explicitly}"
: "${STUDIO_DB_USER:?Set the database user}"
: "${STUDIO_DB_PASSWORD:?Set the database password}"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
export PORT="${PORT:-8091}"
exec "$JAVA_BIN" -Djdk.httpclient.disableRetryConnect=true \
  -Djdk.httpclient.enableAllMethodRetry=false \
  -jar openrule-studio-server/target/openrule-studio-server-1.0.0-SNAPSHOT.jar "$@"
