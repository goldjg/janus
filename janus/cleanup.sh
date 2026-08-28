#!/bin/bash
# JANUS cleanup job entry point
# Runs the CleanupJob main class using the bundled JANUS JAR.
set -euo pipefail

JAR_PATH="/opt/janus/janus.jar"

if [ ! -f "$JAR_PATH" ]; then
    echo "ERROR: JANUS JAR not found at $JAR_PATH" >&2
    exit 1
fi

exec java \
    -cp "$JAR_PATH" \
    io.github.goldjg.janus.CleanupJob \
    "$@"
