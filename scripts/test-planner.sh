#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -d "$out" \
  app/src/main/java/de/touchbridge/app/GestureFramePlanner.java \
  tests/java/de/touchbridge/app/GestureFramePlannerTest.java
java -cp "$out" de.touchbridge.app.GestureFramePlannerTest
