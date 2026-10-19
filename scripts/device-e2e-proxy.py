#!/usr/bin/env python3
"""TEST-058 proxy: forward session NDJSON and retain only touch input metadata."""
import argparse
import datetime
import json
import socket
import threading


def read_qr() -> dict[str, object]:
    with socket.create_connection(("127.0.0.1", 8423), timeout=3) as connection:
        connection.sendall(b"GET_QR\n")
        response = bytearray()
        while not response.endswith(b"\n"):
            chunk = connection.recv(65536)
            if not chunk:
                raise RuntimeError("QR IPC closed without response")
            response.extend(chunk)
    result = json.loads(response)
    if not result.get("uri", "").startswith("twinpad://v1#"):
        raise RuntimeError("server has no active twinpad.v1 QR")
    if not result.get("cemu_live"):
        raise RuntimeError("Cemu is not registered as a live Plugin")
    return result


def write_event(path: str, lock: threading.Lock, event: dict[str, object]) -> None:
    event["time_utc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    with lock, open(path, "a", encoding="utf-8") as output:
        output.write(json.dumps(event, separators=(",", ":")) + "\n")


def pump(source: socket.socket, target: socket.socket, inspect: bool, metrics_path: str, lock: threading.Lock) -> None:
    buffer = bytearray()
    try:
        while True:
            data = source.recv(65536)
            if not data:
                break
            target.sendall(data)
            if inspect:
                buffer.extend(data)
                while b"\n" in buffer:
                    raw, _, remainder = buffer.partition(b"\n")
                    buffer[:] = remainder
                    try:
                        message = json.loads(raw)
                    except (ValueError, UnicodeDecodeError):
                        continue
                    if isinstance(message, dict) and message.get("type") == "input_report":
                        # Never persist token/proof, buttons, sticks, gyro, or QR.
                        fields = {
                            key: message.get(key)
                            for key in ("type", "seq", "touch_x", "touch_y", "touch_pressed")
                        }
                        write_event(metrics_path, lock, fields)
    except OSError:
        pass
    try:
        target.shutdown(socket.SHUT_WR)
    except OSError:
        pass


def handle(client: socket.socket, args: argparse.Namespace, lock: threading.Lock) -> None:
    try:
        upstream = socket.create_connection(("127.0.0.1", args.server_port), timeout=5)
        upstream.settimeout(None)
        client.settimeout(None)
        app_to_server = threading.Thread(
            target=pump, args=(client, upstream, True, args.metrics, lock), daemon=True
        )
        server_to_app = threading.Thread(
            target=pump, args=(upstream, client, False, args.metrics, lock), daemon=True
        )
        app_to_server.start()
        server_to_app.start()
        app_to_server.join()
        server_to_app.join()
        upstream.close()
    except OSError as error:
        write_event(args.metrics, lock, {"type": "proxy_error", "reason": type(error).__name__})
    finally:
        client.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-port", type=int, default=8420)
    parser.add_argument("--listen-port", type=int, default=18420)
    parser.add_argument("--metrics", required=True)
    args = parser.parse_args()

    for port in (args.server_port, 8421, 8423):
        with socket.create_connection(("127.0.0.1", port), timeout=2):
            pass
    read_qr()  # Fail closed unless the real QR and Cemu registration are present.
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("0.0.0.0", args.listen_port))
    listener.listen(8)
    print(
        f"E2E proxy ready: cemu_live=true, listen_port={args.listen_port}; token omitted",
        flush=True,
    )
    lock = threading.Lock()
    while True:
        connection, _ = listener.accept()
        threading.Thread(target=handle, args=(connection, args, lock), daemon=True).start()


if __name__ == "__main__":
    main()
