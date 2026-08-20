#!/usr/bin/env bash
# ============================================================
# HolzynActor clone-and-run launcher (macOS / Linux)
# - Clean start: H2 database is auto-created at <repo>/data
# - If the jar is missing, build.sh is run first (needs JDK21 + Maven + Node)
# ============================================================
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
export HOLOZYN_ACTOR_DATA_DIR="$ROOT/data"
export HOLOZYN_ACTOR_UPLOAD_DIR="$ROOT/uploads"
JAR="$ROOT/backend/target/holzyn-actor-0.1.0.jar"

if [ ! -f "$JAR" ]; then
  echo "[run] jar not found - building first (needs JDK21 + Maven + Node) ..."
  bash "$ROOT/build.sh"
fi

echo "[run] data dir: $HOLOZYN_ACTOR_DATA_DIR"
exec java -jar "$JAR" "$@"
