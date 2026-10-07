#!/usr/bin/env bash
# Instala (o reinstala) el APK debug en el dispositivo Android conectado.
# Uso: ./scripts/install-apk.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"

if [[ ! -f "$APK" ]]; then
    echo "ERROR: no existe el APK. Compila primero: ./scripts/build.sh" >&2
    exit 1
fi

# Localiza adb (PATH o ANDROID_HOME)
if command -v adb >/dev/null 2>&1; then
    ADB="adb"
elif [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
    ADB="$ANDROID_HOME/platform-tools/adb"
else
    echo "ERROR: adb no encontrado (PATH o ANDROID_HOME/platform-tools)." >&2
    exit 1
fi

if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "ERROR: no hay dispositivo conectado. Activa 'Depuración USB' y autoriza la PC." >&2
    exit 1
fi

echo "==> Instalando APK en $( "$ADB" get-state )"
"$ADB" install -r "$APK"

echo ""
echo "OK. Twinpad instalado. Ábrelo y pulsa 'Escanear QR'."
echo "(Ejecuta ./scripts/run-server.sh en el PC para mostrar el QR de pairing)"
