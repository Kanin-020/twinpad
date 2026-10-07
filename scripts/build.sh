#!/usr/bin/env bash
# Compila todo: workspace Rust + APK Android debug.
# Uso: ./scripts/build.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "==> [1/2] Rust: cargo build --workspace"
cargo build --workspace

echo "==> [2/2] Android: ./gradlew assembleDebug"
(cd android && ./gradlew assembleDebug)

APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "OK. APK en: $APK"
