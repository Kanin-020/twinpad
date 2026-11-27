package com.twinpad.android.app.ui

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.twinpad.android.app.R
import com.twinpad.android.app.core.PairingUiState
import com.twinpad.android.app.pairing.PairingViewModel
import com.twinpad.android.app.wire.AmiiboTagCodec
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Lector NFC de Amiibo (CR-126). Solo lectura: nunca escribe el tag físico.
 *
 * El lector se activa únicamente mientras la Activity está visible (`start`),
 * la sesión está vinculada, el juego «cemu» está seleccionado y el ajuste
 * «Activar envío NFC» está activado (CR-137) (`setEnabled`).
 * Cada Amiibo presentado envía una línea `amiibo_tag` con su volcado NTAG215;
 * al retirarlo se envía `present:false`. Una lectura fallida no envía nada.
 */
class AmiiboNfcController(
    private val activity: Activity,
    private val send: (String) -> Unit,
) : NfcAdapter.ReaderCallback {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var enabled = false
    private var readerEnabled = false

    fun bind(
        owner: LifecycleOwner,
        uiState: StateFlow<PairingUiState>,
        selectedGameId: StateFlow<String?>,
        nfcSend: StateFlow<Boolean>,
    ) {
        owner.lifecycleScope.launch {
            combine(uiState, selectedGameId, nfcSend) { state, gameId, send ->
                state is PairingUiState.Linked && gameId == "cemu" && send
            }.collectLatest { setEnabled(it) }
        }
        owner.lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_START -> start()
                    androidx.lifecycle.Lifecycle.Event.ON_STOP -> stop()
                    else -> Unit
                }
            },
        )
    }

    /** UID del tag enviado como presente; null si no hay tag montado. */
    private var activeUid: String? = null

    private val onTagRemoved =
        NfcAdapter.OnTagRemovedListener {
            val uid =
                synchronized(this) {
                    activeUid.also { activeUid = null }
                }
            if (uid != null && started) {
                send(AmiiboTagCodec.REMOVED_LINE)
                Log.i(TAG, "enviado amiibo_tag present=false uid=$uid (tag retirado)")
            }
        }

    fun start() {
        started = true
        applyReaderMode()
    }

    fun stop() {
        started = false
        applyReaderMode()
        sendRemoval()
    }

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        applyReaderMode()
        if (!enabled) sendRemoval()
    }

    private fun applyReaderMode() {
        val adapter =
            adapter ?: run {
                Log.w(TAG, "sin adaptador NFC: no se puede leer Amiibo")
                return
            }
        val shouldRead = started && enabled
        if (shouldRead == readerEnabled) return
        readerEnabled = shouldRead
        Log.i(TAG, "lector NFC activo=$shouldRead (visible=$started, gate=$enabled)")
        if (shouldRead) {
            adapter.enableReaderMode(
                activity,
                this,
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                null,
            )
        } else {
            adapter.disableReaderMode(activity)
        }
    }

    /** Hilo de lectura NFC (no UI): lee, envía y registra la retirada. */
    override fun onTagDiscovered(tag: Tag) {
        if (started && enabled) {
            handleDiscoveredTag(tag)
        }
    }

    private fun handleDiscoveredTag(tag: Tag) {
        Log.i(TAG, "tag NFC detectado: ${tag.id.size} B de UID, tech=${tag.techList.joinToString()}")
        // NTAG215: UID de 7 bytes (14 hex). Otros tags se ignoran.
        if (tag.id.size != UID_BYTES) {
            return
        }
        val uid = tag.id.joinToString("") { "%02x".format(it) }
        if (synchronized(this) { activeUid == uid }) {
            return
        }
        val dump = readDump(tag)
        if (dump == null) {
            Log.w(TAG, "lectura NFC fallida uid=$uid (no se envía nada)")
            return
        }
        synchronized(this) { activeUid = uid }
        send(AmiiboTagCodec.present(uid, dump))
        showToast(activity.getString(R.string.amiibo_nfc_detected, uid.uppercase()))
        Log.i(
            TAG,
            "enviado amiibo_tag present uid=$uid bytes=${dump.size} " +
                "cola=${dump.copyOfRange(TAIL_START_BYTE, TAIL_START_BYTE + TAIL_BYTES).toHex()}",
        )
        // OnTagRemovedListener es API 24; onTagLost del ReaderCallback es API 37.
        handler.post {
            adapter?.ignore(tag, REMOVAL_DEBOUNCE_MS, onTagRemoved, handler)
        }
    }

    private fun sendRemoval() {
        val uid =
            synchronized(this) {
                activeUid.also { activeUid = null }
            }
        if (uid != null) {
            send(AmiiboTagCodec.REMOVED_LINE)
            Log.i(TAG, "enviado amiibo_tag present=false uid=$uid")
        }
    }

    /**
     * NTAG215 = 135 páginas (0–134). Las 33 lecturas de 4 páginas llegan a la
     * página 131; la cola se lee solapando desde la 131 porque pedir la 135
     * (inexistente) hace que el tag responda NAK y se pierda la lectura entera.
     */
    private fun readDump(tag: Tag): ByteArray? {
        val ultralight = MifareUltralight.get(tag) ?: return null
        return try {
            ultralight.connect()
            val dump = ByteArray(AmiiboTagCodec.DUMP_BYTES)
            var offset = 0
            while (offset < AmiiboTagCodec.DUMP_BYTES - TAIL_BYTES) {
                val chunk = ultralight.readPages(offset / PAGE_BYTES)
                chunk.copyInto(dump, offset)
                offset += chunk.size
            }
            val tail = ultralight.readPages(TAIL_START_PAGE)
            tail.copyInto(
                dump,
                AmiiboTagCodec.DUMP_BYTES - TAIL_BYTES,
                TAIL_SKIP_BYTES,
                tail.size,
            )
            dump
        } catch (_: IOException) {
            null
        } finally {
            runCatching { ultralight.close() }
        }
    }

    /** Aviso al usuario; `onTagDiscovered` corre en un hilo de lectura NFC. */
    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(activity.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "TwinpadAmiibo"
        private const val PAGE_BYTES = 4
        private const val UID_BYTES = 7
        private const val REMOVAL_DEBOUNCE_MS = 500

        /** Página inicial de la lectura solapada de cola (131–134). */
        private const val TAIL_START_PAGE = 131

        /** Bytes del bloque de cola ya cubiertos por las lecturas completas (página 131). */
        private const val TAIL_SKIP_BYTES = 4

        /** Bytes de cola que completa el volcado: 540 − 528. */
        private const val TAIL_BYTES = 12

        /** Offset del bloque dynamicLock/cfg0/cfg1 que valida Cemu. */
        private const val TAIL_START_BYTE = 520

        internal fun attach(
            activity: Activity,
            viewModel: PairingViewModel,
            nfcSend: StateFlow<Boolean>,
        ) {
            AmiiboNfcController(activity, viewModel::sendSessionLine)
                .bind(activity as LifecycleOwner, viewModel.uiState, viewModel.selectedGameId, nfcSend)
        }
    }
}
