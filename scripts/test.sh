#!/usr/bin/env bash
# Corre TODOS los gates del proyecto (workflow §8): formato, lints y tests.
# Uso: ./scripts/test.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "==> [1/6] Rust: cargo fmt --check"
cargo fmt --all -- --check

echo "==> [2/6] Rust: cargo clippy -D warnings"
cargo clippy --workspace --all-targets -- -D warnings

echo "==> [3/6] Rust: cargo test --workspace"
cargo test --workspace

echo "==> [4/6] Android: ./gradlew ktlintCheck"
(cd android && ./gradlew ktlintCheck)

echo "==> [5/6] Android: ./gradlew detekt"
(cd android && ./gradlew detekt)

echo "==> [6/6] Android: ./gradlew testDebugUnitTest"
(cd android && ./gradlew testDebugUnitTest)

echo ""
echo "OK. Todos los gates en verde."
