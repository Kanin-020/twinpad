#!/usr/bin/env bash
# TEST-056..058: authenticated Pairing, Cemu video and touch on a real device.
# The current server and Cemu are reused and never terminated by this script.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-adb}"
DEVICE_SERIAL="${ANDROID_SERIAL:-}"
SERVER_PORT="${TWINPAD_SERVER_PORT:-8420}"
PROXY_PORT="${TWINPAD_E2E_PROXY_PORT:-18420}"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
RESULT_DIR="${TWINPAD_E2E_RESULTS_DIR:-$ROOT/artifacts/test-results/device-e2e/$RUN_ID}"
PROXY_PID=""
ADDED_REVERSES=()
cleanup() {
    for port in "${ADDED_REVERSES[@]}"; do
        "$ADB" -s "$DEVICE_SERIAL" reverse --remove "tcp:$port" >/dev/null 2>&1 || true
    done
    if [[ -n "$PROXY_PID" ]] && kill -0 "$PROXY_PID" 2>/dev/null; then
        kill -TERM "$PROXY_PID" 2>/dev/null || true
        wait "$PROXY_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM
fail() { echo "ERROR: $*" >&2; exit 1; }

if [[ -z "$DEVICE_SERIAL" ]]; then
    mapfile -t DEVICES < <("$ADB" devices | awk 'NR > 1 && $2 == "device" {print $1}')
    [[ ${#DEVICES[@]} -eq 1 ]] || fail "conecta un solo dispositivo adb o especifica ANDROID_SERIAL"
    DEVICE_SERIAL="${DEVICES[0]}"
fi
"$ADB" -s "$DEVICE_SERIAL" get-state >/dev/null 2>&1 || fail "dispositivo adb no disponible"
mkdir -p "$RESULT_DIR"
printf '%s\n' "run_id=$RUN_ID" "device=$DEVICE_SERIAL" "server_port=$SERVER_PORT" "proxy_port=$PROXY_PORT" >"$RESULT_DIR/run.txt"

python3 "$ROOT/scripts/device-e2e-proxy.py" \
    --server-port "$SERVER_PORT" \
    --listen-port "$PROXY_PORT" \
    --metrics "$RESULT_DIR/input-reports.jsonl" \
    >"$RESULT_DIR/proxy.log" 2>&1 &
PROXY_PID=$!
for _ in $(seq 1 50); do
    if ! kill -0 "$PROXY_PID" 2>/dev/null; then
        cat "$RESULT_DIR/proxy.log" >&2
        fail "proxy E2E no pudo verificar/iniciar; ¿servidor/Cemu vivos?"
    fi
    if ss -H -ltn "sport = :$PROXY_PORT" | grep -q .; then break; fi
    sleep 0.1
done
ss -H -ltn "sport = :$PROXY_PORT" | grep -q . || fail "proxy E2E no abrió el puerto $PROXY_PORT"
cat "$RESULT_DIR/proxy.log"

add_reverse() {
    local port="$1"
    if ! "$ADB" -s "$DEVICE_SERIAL" reverse --list | grep -qE "tcp:$port[[:space:]]+tcp:$port"; then
        "$ADB" -s "$DEVICE_SERIAL" reverse "tcp:$port" "tcp:$port"
        ADDED_REVERSES+=("$port")
    fi
}
add_reverse 8423
add_reverse "$PROXY_PORT"
echo "==> Ejecutando TEST-056..TEST-058 en $DEVICE_SERIAL; evidencia: $RESULT_DIR"
set +e
(
    cd "$ROOT/android" && ./gradlew --no-daemon :app:connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.class=com.twinpad.android.app.e2e.TwinpadDeviceE2eTest \
        -Pandroid.testInstrumentationRunnerArguments.run_id="$RUN_ID" \
        -Pandroid.testInstrumentationRunnerArguments.server_port="$PROXY_PORT"
)
TEST_STATUS=$?
set -e
"$ADB" -s "$DEVICE_SERIAL" logcat -d -s TwinpadE2E:I >"$RESULT_DIR/device.log" 2>&1 || true
if [[ $TEST_STATUS -ne 0 ]]; then
    echo "ERROR: Gradle/device test failed (exit $TEST_STATUS); evidence: $RESULT_DIR" >&2
    exit "$TEST_STATUS"
fi

python3 - "$RESULT_DIR/input-reports.jsonl" "$RESULT_DIR/touch-metrics.json" <<'PY'
import json, sys
source, output = sys.argv[1:]
try:
    with open(source, encoding="utf-8") as stream:
        reports = [json.loads(line) for line in stream if line.strip()]
except FileNotFoundError:
    raise SystemExit("ERROR: no se capturaron input_report del canal real de sesión")
seqs = [item["seq"] for item in reports]
if any(new <= old for old, new in zip(seqs, seqs[1:])):
    raise SystemExit("ERROR: los seq de input_report no son estrictamente crecientes")
pressed = [item for item in reports if item.get("touch_pressed") is True]
if not pressed:
    raise SystemExit("ERROR: no llegó touch_pressed=true al socket del servidor")
transitions = [
    (previous, current)
    for previous, current in zip(reports, reports[1:])
    if previous.get("touch_pressed") is True and current.get("touch_pressed") is False
]
if not transitions:
    raise SystemExit("ERROR: no se observó transición pressed→released posterior al toque")
metrics = {
    "input_reports_seen": len(reports),
    "touch_pressed_reports": len(pressed),
    "touch_release_transitions": len(transitions),
    "last_seq": seqs[-1],
}
with open(output, "w", encoding="utf-8") as stream:
    json.dump(metrics, stream, indent=2)
print(f"Input táctil confirmado por proxy: {metrics}")
PY

echo "OK: TEST-056..TEST-058 pasaron; evidencia: $RESULT_DIR"
