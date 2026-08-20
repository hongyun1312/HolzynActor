#!/usr/bin/env bash
# ============================================================
# HolzynActor full build (macOS / Linux): frontend -> static -> jar
# Requires: JDK 21 + Maven + Node.js (npm)
# ============================================================
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"

# frontend build
echo "[build] npm run build ..."
( cd "$ROOT/frontend" && npm run build )

# sync dist -> backend static
echo "[build] sync dist -> backend/src/main/resources/static ..."
rm -rf "$ROOT/backend/src/main/resources/static/assets"
mkdir -p "$ROOT/backend/src/main/resources/static"
cp -R "$ROOT/frontend/dist/." "$ROOT/backend/src/main/resources/static/"
rm -rf "$ROOT/backend/target/classes/static"

# backend package
echo "[build] mvn clean package ..."
( cd "$ROOT/backend" && mvn -q -Dmaven.test.skip=true clean package )

echo "[build] OK: backend/target/holzyn-actor-0.1.0.jar"
