#!/bin/bash
# JANUS cleanup job entry point
# Runs the CleanupJob main class using the bundled JANUS JAR.
set -euo pipefail

JAR_PATH="/opt/janus/janus-cleanup.jar"

if [ ! -f "$JAR_PATH" ]; then
    echo '{"operation":"cleanup_start","outcome":"failed","reason":"cleanup_jar_missing"}' >&2
    exit 1
fi

exec java -jar "$JAR_PATH" "$@"
