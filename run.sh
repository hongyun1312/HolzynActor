#!/usr/bin/env bash
# ============================================================
# HolzynActor clone-and-run launcher (macOS / Linux)
# - Uses <repo>/data (local H2 database, auto-created) and <repo>/uploads
# - Requires JDK 21; uses a prebuilt jar from backend/target if present
# ============================================================
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
export HOLOZYN_ACTOR_DATA_DIR="$ROOT/data"
export HOLOZYN_ACTOR_UPLOAD_DIR="$ROOT/uploads"
JAR="$ROOT/backend/target/holzyn-actor-1.0.0-beta.1.jar"

if [ ! -f "$JAR" ]; then
  echo "[run] jar not found - building first (needs JDK21 + Maven + Node) ..."
  bash "$ROOT/build.sh"
fi

echo "[run] data dir: $HOLOZYN_ACTOR_DATA_DIR"
exec java -jar "$JAR" "$@"
