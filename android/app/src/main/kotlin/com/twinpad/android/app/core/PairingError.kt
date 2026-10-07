package com.twinpad.android.app.core

/**
 * Errores de la vinculación — códigos exactos de SPEC-001 §9 y SPEC-002 §9.
 * No se inventan errores observables nuevos.
 */
enum class PairingError(val wireCode: String?) {
    /** E1: conexión TCP rechazada o sin respuesta en 5 s. */
    ServerUnavailable("E1"),

    /** E2: QR expirado según el servidor (SPEC-001 R3). */
    QrExpired("E2"),

    /** E3: vinculación rechazada (token consumido/desconocido o proof inválido). */
    Rejected("E3"),

    /** E4: mensaje del servidor no parseable o fuera de constraints. */
    InvalidResponse("E4"),

    /** E5: payload del QR no conforme a SPEC-001 §4.1. */
    QrInvalid(null),

    /** PERMISO: cámara denegada (SPEC-002 §8.1). */
    CameraDenied(null),

    /** SESIÓN: corte de conexión estando vinculado (SPEC-002 §9). */
    SessionEnded(null),
}

/**
 * Excepción portadora de un [PairingError]; permite propagar el error por
 * llamadas que fallan (parseo, red) sin usar excepciones genéricas.
 * Conserva la causa original para diagnóstico (ENG §6).
 */
class PairingException(
    val error: PairingError,
    cause: Throwable? = null,
) : RuntimeException(error.name, cause)

/** Mapeo del código textual del wire (SPEC-001 §4.2) a [PairingError]. */
fun wireCodeToPairingError(code: String): PairingError =
    when (code) {
        "E1" -> PairingError.ServerUnavailable
        "E2" -> PairingError.QrExpired
        "E3" -> PairingError.Rejected
        "E4" -> PairingError.InvalidResponse
        "E5" -> PairingError.QrInvalid
        else -> PairingError.InvalidResponse
    }
