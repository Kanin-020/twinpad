#!/usr/bin/env bash
# Muestra un QR de prueba en la terminal SIN levantar el servidor.
# Útil para validar el render del QR y el decodificador de la app:
# al escanearlo, la app mostrará "Servidor no disponible" (E1, SPEC-002 §9).
# Uso: ./scripts/test-qr.sh [uri]   (default: URI de muestra §4.1)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

cargo run -q -p twinpad-server --example test_qr -- "$@"
