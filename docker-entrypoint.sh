#!/bin/sh
# Derive Snowflake worker ID from the container hostname's trailing digit(s).
# Docker Compose names replicas like: url-shortner-swiftlink-api-1, ...-2, ...-3
# This gives each replica a unique worker ID without complex orchestration.
RAW_ID=$(hostname | grep -oE '[0-9]+$' || echo "0")
WORKER_ID=$(( ${RAW_ID:-0} % 1024 ))
export APP_SNOWFLAKE_WORKERID="$WORKER_ID"
echo "Starting SwiftLink with workerId=$APP_SNOWFLAKE_WORKER_ID (hostname=$(hostname))"
exec java -jar app.jar
