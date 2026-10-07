package com.twinpad.android.app.ui

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Adaptador de cámara (DES-002 §4): ejecuta los efectos `StartCamera` /
 * `StopCamera` con CameraX. El decodificador de QR (analizador) entra con
 * la iteración de instrumentación en dispositivo (DES-002 §14 diferido);
 * `onQrScanned` queda listo para recibirlo.
 */
class PairingCameraController(private val context: Context) {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    fun bind(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner?,
    ) {
        val owner = lifecycleOwner ?: return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview =
                    Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                // El decodificador de QR (ImageAnalysis + analyzer) se integra
                // en la iteración de instrumentación (DES-002 §14 diferido):
                // cada QR leído invocará onQrScanned(raw).
            } catch (e: Exception) {
                // La cámara no está disponible; la máquina permanece en U2.
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun start() {
        // El bind ocurre al componer la preview; nada adicional aquí.
    }

    fun stop() {
        cameraProvider?.unbindAll()
    }

    fun release() {
        cameraExecutor.shutdown()
    }
}
