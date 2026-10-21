package com.twinpad.android.app.pairing

import com.twinpad.android.app.core.PairingStateMachine
import com.twinpad.android.app.pairing.network.TcpPairingClient
import com.twinpad.android.app.stream.StreamStateMachine
import com.twinpad.android.app.stream.StreamStats
import kotlinx.coroutines.CoroutineScope

/** Collaborators that provide the active pairing and stream context to media handling. */
internal data class PairingMediaDependencies(
    val scope: CoroutineScope,
    val machine: PairingStateMachine,
    val network: TcpPairingClient,
    val streamMachine: StreamStateMachine,
    val streamStats: StreamStats,
)
