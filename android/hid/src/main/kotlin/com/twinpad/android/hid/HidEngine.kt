package com.twinpad.android.hid

/**
 * Motor HID (Android) — módulo `Motor HID (Android)` (ARCH §2).
 *
 * Responsabilidad: presentar el celular como Gamepad HID vía Bluetooth y
 * codificar inputs al Signature HID (CTX-C5) — REQ-003.
 *
 * Este módulo es la única frontera Kotlin ↔ Rust por FFI junto al wrapper
 * del SDK (ENG §2). Sin comportamiento propio hasta que su SPEC esté
 * `Approved` (el descriptor HID aún no tiene SPEC).
 */
class HidEngine
