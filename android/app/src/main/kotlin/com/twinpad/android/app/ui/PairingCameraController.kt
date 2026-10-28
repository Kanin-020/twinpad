package com.twinpad.android.app.ui

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.twinpad.android.app.wire.CAMERA_HEIGHT
import com.twinpad.android.app.wire.CAMERA_WIDTH
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Adaptador de cámara (DES-002 §4): ejecuta los efectos `StartCamera` /
 * `StopCamera` con CameraX y decodifica QRs con ML Kit (SPEC-002 §6 U2:
 * cada payload crudo llega al evento `QrScanned` para su validación §4.1).
 */class PairingCameraController(


    private val context: Context,
    private val onQrScanned: (String) -> Unit,
) {
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
                val analysis =
                    ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(cameraExecutor, QrAnalyzer(onQrScanned)) }
                provider.unbindAll()
                provider.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (e: Exception) {
                // La cámara no está disponible; la máquina permanece en U2
                // (SPEC-002 §6). Dejamos rastro: un preview negro sin log
                // sería indistinguible de un error de cableado.
                android.util.Log.w("PairingCamera", "Cámara no disponible", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun start() {
        // El bind ocurre al componer la preview; nada adicional aquí.
    }

    fun stop() {
        cameraProvider?.unbindAll()
        encoder?.stop()
        encoder = null
    }

    private var encoder: CameraStreamEncoder? = null

    /** Para el envío de la cámara frontal (CR-034) sin tocar el QR. */
    fun stopFrontStream() {
        encoder?.stop()
        encoder = null
    }

    /**
     * CR-034: envía la cámara frontal a 320x240 / 30 fps como `camera_frame`.
     * Usa el mismo proveedor de CameraX que el QR; la superficie de entrada
     * del codificador es el destino de la vista previa.
     */
    fun startFrontStream(
        lifecycleOwner: LifecycleOwner,
        onLine: (String) -> Unit,
    ) {
        if (encoder != null) return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val streamEncoder = CameraStreamEncoder(onLine)
                val surface = streamEncoder.start()
                encoder = streamEncoder
                val preview =
                    Preview.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        android.util.Size(CAMERA_WIDTH, CAMERA_HEIGHT),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                    ),
                                )
                                .build(),
                        )
                        .build()
                        .also {
                            it.setSurfaceProvider { request ->
                                request.provideSurface(surface, cameraExecutor) { }
                            }
                        }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
            } catch (e: Exception) {
                android.util.Log.w("PairingCamera", "Cámara frontal no disponible", e)
                encoder?.stop()
                encoder = null
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        cameraExecutor.shutdown()
    }
}

/**
 * Analizador ML Kit: decodifica QRs y entrega el string crudo a la máquina
 * de estados, que valida §4.1 (el analyzer no valida: única responsabilidad).
 */
private class QrAnalyzer(
    private val onQrScanned: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val scanner =
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner
            .process(input)
            .addOnSuccessListener { barcodes ->
                // El primero que cumpla el esquema twinpad basta (SPEC-002 §7 paso 2);
                // la validación del payload vive en la máquina de estados (§4.1).
                val raw = barcodes.firstOrNull()?.rawValue
                if (raw != null) onQrScanned(raw)
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}
