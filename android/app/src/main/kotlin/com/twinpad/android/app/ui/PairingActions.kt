package com.twinpad.android.app.ui

/** Acciones de la Pantalla de Pairing (DES-002: ≤ 3 posicionales, ENG P4). */
data class PairingActions(
    val onScan: () -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
    val onQrScanned: (String) -> Unit,
)
