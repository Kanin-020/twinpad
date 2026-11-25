#!/usr/bin/env python3
"""Valida el manifiesto de compatibilidad contra los esquemas del código.

Uso: ./scripts/check-compatibility.py [version]
Sin versión solo valida; con versión imprime el manifiesto de esa release.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEMPLATE = ROOT / ".github" / "compatibility.template.json"
CORE_HANDSHAKE = ROOT / "core" / "src" / "message.rs"
ANDROID_HANDSHAKE = (
    ROOT
    / "android"
    / "app"
    / "src"
    / "main"
    / "kotlin"
    / "com"
    / "twinpad"
    / "android"
    / "app"
    / "wire"
    / "HandshakeCodec.kt"
)
VERSION_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$")


class CompatibilityError(Exception):
    """Manifiesto o esquema incompatible."""


def read_protocol(path: Path, pattern: str) -> str:
    match = re.search(pattern, path.read_text(encoding="utf-8"), re.MULTILINE)
    if match is None:
        raise CompatibilityError(f"no se encontró el esquema en {path}")
    return match.group(1)


def validate_template(manifest: dict) -> str:
    if manifest.get("schema") != "twinpad.compatibility.v1":
        raise CompatibilityError("schema debe ser twinpad.compatibility.v1")
    protocol = manifest.get("protocol")
    if protocol != "twinpad.v1":
        raise CompatibilityError("protocol debe ser twinpad.v1")
    for field in ("release", "server", "android"):
        if manifest.get(field) != "release":
            raise CompatibilityError(f"{field} debe ser el marcador 'release'")

    integrations = manifest.get("integrations")
    if not isinstance(integrations, dict) or not integrations:
        raise CompatibilityError("integrations debe ser un objeto no vacío")
    for key, integration in integrations.items():
        if not isinstance(integration, dict):
            raise CompatibilityError(f"integración '{key}' inválida")
        if not integration.get("name"):
            raise CompatibilityError(f"integración '{key}' sin name")
        if not str(integration.get("repository", "")).startswith("https://github.com/"):
            raise CompatibilityError(f"integración '{key}' sin repository de GitHub")
        if not str(integration.get("releases", "")).endswith("/releases/latest"):
            raise CompatibilityError(f"integración '{key}' sin enlace releases/latest")
        if integration.get("protocol") != protocol:
            raise CompatibilityError(f"integración '{key}' con protocolo distinto")
    return protocol


def main() -> int:
    try:
        manifest = json.loads(TEMPLATE.read_text(encoding="utf-8"))
        protocol = validate_template(manifest)

        core_protocol = read_protocol(
            CORE_HANDSHAKE,
            r'^pub const SCHEMA: &str = "([^"]+)";',
        )
        android_protocol = read_protocol(
            ANDROID_HANDSHAKE,
            r'^const val SCHEMA: String = "([^"]+)"',
        )
        if core_protocol != protocol:
            raise CompatibilityError(
                f"core usa '{core_protocol}' y el manifiesto declara '{protocol}'"
            )
        if android_protocol != protocol:
            raise CompatibilityError(
                f"Android usa '{android_protocol}' y el manifiesto declara '{protocol}'"
            )

        if len(sys.argv) < 2 or not sys.argv[1]:
            print(f"compatibilidad: manifiesto y esquemas OK ({protocol})")
            return 0

        version = sys.argv[1].removeprefix("v")
        if not VERSION_RE.fullmatch(version):
            raise CompatibilityError(f"versión inválida: {sys.argv[1]}")

        manifest["release"] = f"v{version}"
        manifest["server"] = version
        manifest["android"] = version
        print(json.dumps(manifest, ensure_ascii=False, indent=2))
        return 0
    except (CompatibilityError, OSError, json.JSONDecodeError) as error:
        print(f"compatibilidad: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
