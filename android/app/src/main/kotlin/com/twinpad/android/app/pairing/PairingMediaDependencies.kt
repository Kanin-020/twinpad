package com.twinpad.android.app.pairing

import com.twinpad.android.app.core.PairingStateMachine
import com.twinpad.android.app.pairing.network.TcpPairingClient
import com.twinpad.android.app.stream.StreamStateMachine
import com.twinpad.android.app.stream.StreamStats
import kotlinx.coroutines.CoroutineScope

/** Colaboradores inyectados a [PairingMediaSession] (DES-002 §11: la I/O
 * vive en la capa pairing; el core de estados no conoce Android). */
internal data class PairingMediaDependencies(
    /** Scope de corutinas para el loop de entrega de AU y reintentos. */
    val scope: CoroutineScope,
    /** Máquina de estados de Pairing (fuente de Linked + session_key). */
    val machine: PairingStateMachine,
    /** Conexión TCP de control que aporta el host remoto. */
    val network: TcpPairingClient,
    /** Máquina de estados del stream (SPEC-003 §6). */
    val streamMachine: StreamStateMachine,
    /** Métricas de etapas del video (SPEC-003 §7 HUD). */
    val streamStats: StreamStats,
)
