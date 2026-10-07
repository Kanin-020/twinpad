#!/usr/bin/env bash
# Construye y ejecuta el Servidor PC: QR real de vinculación en la terminal
# + listener en la LAN para el pairing (el QR se regenera cada 300 s, R7).
# Uso: ./scripts/run-server.sh [puerto]   (default: 8420)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "==> Compilando twinpad-server..."
cargo build -q -p twinpad-server

PORT="${1:-8420}"
echo "==> Servidor en el puerto $PORT (Ctrl+C para salir)"
exec ./target/debug/twinpad-server "$PORT"
