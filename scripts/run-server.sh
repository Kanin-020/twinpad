#!/usr/bin/env bash
# Construye y ejecuta el Servidor PC: QR real de vinculación en la terminal
# + listener en la LAN para el pairing (el QR se regenera cada 300 s, R7).
# Uso: ./scripts/run-server.sh [puerto]   (default: 8420)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

UDP_MEDIA_PORT="${UDP_MEDIA_PORT:-8425}"
# Encodificador: el default del repo (30 000 kbps) emite ~3 400 datagrams/s
# por el UDP §4.4 y desborda el lector del teléfono (~2 300 datagrams/s con
# GCM+reensamblado en Kotlin): socket buffer desbordado → drops → AUs que no
# completan → FLUSH loop y 0 fps. 10 000 kbps ≈ 920 datagrams/s queda debajo
# de la capacidad del cliente (margen >2×). Súbelo solo si el enlace es
# robusto; la escalera CR-058 no puede pasar del techo que aquí fijes.
TWINPAD_VIDEO_BITRATE_KBPS="${TWINPAD_VIDEO_BITRATE_KBPS:-10000}"
export TWINPAD_VIDEO_BITRATE_KBPS

# CR-062 (SPEC-003 §4.4): el canal media UDP preferente comparte el puerto
# del TCP media (8425). Con firewalld activo y el UDP sin abrir, el teléfono
# cae al fallback TCP (§8.5) cuyo SNDBUF acumula olas de ~100–160 ms. El TCP
# de fallback no necesita regla nueva: si ya conectas por TCP, sus puertos ya
# están abiertos; aquí solo aseguramos el UDP, una sola vez, con `sudo`.
fw_host() {
  # firewall-cmd en este host; desde toolbox/podman usa flatpak-spawn --host.
  if command -v firewall-cmd >/dev/null 2>&1; then
    firewall-cmd "$@"
  elif command -v flatpak-spawn >/dev/null 2>&1 && \
     flatpak-spawn --host firewall-cmd --state >/dev/null 2>&1; then
    flatpak-spawn --host firewall-cmd "$@"
  else
    return 127
  fi
}

ensure_udp_media_port() {
  local port="${1:-8425}"
  if command -v firewall-cmd >/dev/null 2>&1 || command -v flatpak-spawn >/dev/null 2>&1; then
    if ! fw_host --state >/dev/null 2>&1; then
      if systemctl is-active --quiet firewalld 2>/dev/null; then
        echo "==> firewall-cmd no accesible desde el toolbox: firewalld está ACTIVO en el host."
        echo "==> Ejecuta en el host (una vez): sudo firewall-cmd --permanent --add-port=${port}/udp && sudo firewall-cmd --reload"
      fi
      return 0 # sin firewall gestionado o sin CLI: nada que hacer aquí
    fi
    if fw_host --query-port="${port}/udp" 2>/dev/null | grep -q '^yes$'; then
      echo "==> UDP ${port}/udp ya abierto en firewalld (canal media preferente activo)."
      return 0
    fi
    echo "==> El canal media UDP preferente (CR-062) requiere ${port}/udp abierto en firewalld."
    echo "==> Pidiendo sudo una sola vez (--permanent se conserva tras reiniciar):"
    if fw_host --permanent --add-port="${port}/udp" >/dev/null 2>&1 \
      && fw_host --reload >/dev/null 2>&1; then
      echo "==> UDP ${port}/udp abierto (único paso en este host)."
    else
      echo "==> AVISO: no se pudo abrir UDP ${port}/udp (sudo cancelado/no disponible)."
      echo "==> El stream arrancará por TCP fallback hasta que ejecutes en el host:"
      echo "==>   sudo firewall-cmd --permanent --add-port=${port}/udp && sudo firewall-cmd --reload"
    fi
    return 0
  fi
  # Sin firewalld y sin flatpak: loopback ufw u otro firewall son config manual.
  echo "==> Sin firewall-cmd en el PATH: si usas ufw/iptables, abre ${port}/udp manualmente."
}

ensure_udp_media_port "$UDP_MEDIA_PORT"

echo "==> Compilando twinpad-server (release)..."
cargo build -q --release -p twinpad-server

PORT="${1:-8420}"
echo "==> Servidor release en el puerto $PORT (Ctrl+C para salir)"
echo "==> Canal media: UDP ${UDP_MEDIA_PORT} preferente + TCP fallback (CR-062 §4.4/§8.5)."
exec ./target/release/twinpad-server "$PORT"
