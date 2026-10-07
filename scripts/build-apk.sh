#!/usr/bin/env bash
# Compila el APK de Twinpad en la variante indicada.
# Uso: ./scripts/build-apk.sh [debug|release]   (default: debug)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VARIANT="${1:-debug}"

case "$VARIANT" in
    debug)   TASK="assembleDebug";  OUT="app-debug.apk" ;;
    release) TASK="assembleRelease"; OUT="app-release.apk" ;;
    *)
        echo "ERROR: variante desconocida '$VARIANT'. Usa: debug | release" >&2
        exit 1
        ;;
esac

echo "==> Android: ./gradlew $TASK"
(cd android && ./gradlew "$TASK")

APK="$ROOT/android/app/build/outputs/apk/$VARIANT/$OUT"
if [[ ! -f "$APK" ]]; then
    echo "ERROR: no se generó $APK" >&2
    exit 1
fi

SIZE="$(du -h "$APK" | cut -f1)"
echo ""
echo "OK. APK $VARIANT en: $APK ($SIZE)"
