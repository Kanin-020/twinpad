package com.twinpad.android.app.e2e

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twinpad.android.app.R
import com.twinpad.android.app.core.PairingEvent
import com.twinpad.android.app.core.PairingUiState
import com.twinpad.android.app.pairing.PairingViewModel
import com.twinpad.android.app.stream.StreamState
import com.twinpad.android.app.ui.PairingActivity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

/** TEST-056..058 — real Pairing, Cemu video presentation and LAN touch inputs. */
@RunWith(AndroidJUnit4::class)
class TwinpadDeviceE2eTest {
    @Test
    fun live_cemu_session_pairs_presents_video_and_transmits_drc_touch() {
        val runId = InstrumentationRegistry.getArguments().getString("run_id") ?: "unspecified"
        val qr = fetchCurrentQr()
        assertTrue("A registered Cemu Plugin is required", qr.cemuLive)
        val scenario = ActivityScenario.launch(PairingActivity::class.java)
        try {
            lateinit var viewModel: PairingViewModel
            val pairingStartedAt = SystemClock.elapsedRealtime()
            scenario.onActivity { activity ->
                val field = PairingActivity::class.java.getDeclaredField("viewModel")
                field.isAccessible = true
                viewModel = field.get(activity) as PairingViewModel
                // The camera payload is already decoded. Supply the SO's
                // permission-granted callback so no dialog/camera opens.
                viewModel.onCameraPermissionResult(granted = true)
                // QR validation → TCP → real authenticated handshake.
                viewModel.onEvent(PairingEvent.QrScanned(qr.uri))
            }

            awaitCondition("authenticated Pairing and Cemu selection") {
                (viewModel.uiState.value is PairingUiState.Linked && viewModel.selectedGameId.value == "cemu") ||
                    viewModel.uiState.value is PairingUiState.Failure
            }
            assertTrue("Pairing failed: ${viewModel.uiState.value}", viewModel.uiState.value is PairingUiState.Linked)
            assertEquals("cemu", viewModel.selectedGameId.value)
            val pairingMs = SystemClock.elapsedRealtime() - pairingStartedAt

            awaitCondition("first presented Cemu Framebuffer") {
                viewModel.lastFrame.value != null || viewModel.uiState.value is PairingUiState.Failure
            }
            assertNotNull("Cemu did not deliver a Framebuffer", viewModel.lastFrame.value)
            assertEquals(StreamState.STREAMING, viewModel.streamState.value.state)
            scenario.onActivity { activity ->
                val video = activity.findViewById<ImageView>(R.id.video_view)
                assertNotNull("The received Framebuffer was not published to the UI", video.drawable)
                assertTrue(
                    "The visible UI drawable is not the current Framebuffer",
                    (video.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ===
                        viewModel.lastFrame.value?.bitmap,
                )
            }

            val firstFrame = viewModel.lastFrame.value!!
            val measuredAt = SystemClock.elapsedRealtime()
            Thread.sleep(FRAME_MEASUREMENT_MS)
            val laterFrame = viewModel.lastFrame.value
            assertNotNull("Cemu Framebuffer stopped arriving", laterFrame)
            assertTrue("No new frame arrived during measurement", laterFrame!!.frameNumber > firstFrame.frameNumber)
            val measuredMs = SystemClock.elapsedRealtime() - measuredAt
            val frameDelta = laterFrame.frameNumber - firstFrame.frameNumber
            val streamStats = viewModel.streamStats.snapshot()

            val arguments = InstrumentationRegistry.getArguments()
            val proxyPort = arguments.getString("server_port")?.toIntOrNull() ?: SERVER_PORT
            assertEquals(
                "The E2E app must use the loopback proxy so the harness can observe the real session input",
                proxyPort,
                qr.proxyPort,
            )
            var surfaceLaidOut = false
            val layoutDeadline = SystemClock.elapsedRealtime() + LAYOUT_TIMEOUT_MS
            while (!surfaceLaidOut && SystemClock.elapsedRealtime() < layoutDeadline) {
                scenario.onActivity { activity ->
                    val surfaceContainer = activity.findViewById<ViewGroup>(R.id.surface_container)
                    val surface = surfaceContainer.getChildAt(0)
                    surfaceLaidOut = surface != null && surface.width > 0 && surface.height > 0
                }
                if (!surfaceLaidOut) SystemClock.sleep(POLL_INTERVAL_MS)
            }
            assertTrue("Touch surface did not finish layout", surfaceLaidOut)
            scenario.onActivity { activity ->
                val surfaceContainer = activity.findViewById<ViewGroup>(R.id.surface_container)
                val surface = surfaceContainer.getChildAt(0)
                assertNotNull("Touch surface view is missing", surface)
                dispatchTouch(surface!!, MotionEvent.ACTION_DOWN, 0.50f, 0.52f)
                dispatchTouch(surface, MotionEvent.ACTION_MOVE, 0.54f, 0.56f)
                dispatchTouch(surface, MotionEvent.ACTION_UP, 0.54f, 0.56f)
            }
            Thread.sleep(TOUCH_SETTLE_MS)
            assertTrue("The session ended after sending touch input", viewModel.uiState.value is PairingUiState.Linked)

            Log.i(
                LOG_TAG,
                "run=$runId TEST-056 pairing_ms=$pairingMs linked=true source=cemu; " +
                    "TEST-057 frame=${laterFrame.frameNumber} size=${laterFrame.width}x${laterFrame.height} " +
                    "frames=$frameDelta elapsed_ms=$measuredMs receive_fps=${streamStats.receiveFps} " +
                    "presented_fps=${streamStats.presentedFps} kbps=${streamStats.kbps} " +
                    "gaps=${streamStats.frameNumberGaps} rejected=${streamStats.rejectedFrames}; " +
                    "TEST-058 touch_gesture_dispatched=true session_alive=true",
            )
        } finally {
            scenario.close()
        }
    }

    private fun fetchCurrentQr(): PairingQr =
        Socket(QR_IPC_HOST, QR_IPC_PORT).use { socket ->
            socket.soTimeout = QR_IPC_TIMEOUT_MS
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            writer.write("GET_QR\n")
            writer.flush()
            val response = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine()
            val json = Json.parseToJsonElement(response).jsonObject
            val arguments = InstrumentationRegistry.getArguments()
            val proxyPort = arguments.getString("server_port")?.toIntOrNull() ?: SERVER_PORT
            val originalUri = json.getValue("uri").jsonPrimitive.content
            val proxyUri = Regex("([&#]p=)\\d+").replace(originalUri) { match -> "${match.groupValues[1]}$proxyPort" }
            require(proxyUri != originalUri) { "E2E QR URI had no server port to redirect" }
            PairingQr(
                uri = proxyUri,
                proxyPort = proxyPort,
                cemuLive = json.getValue("cemu_live").jsonPrimitive.content.toBooleanStrict(),
            )
        }

    private fun awaitCondition(
        description: String,
        predicate: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + CONDITION_TIMEOUT_MS
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        assertTrue("Timed out waiting for $description", predicate())
    }

    private fun dispatchTouch(
        view: View,
        action: Int,
        normalizedX: Float,
        normalizedY: Float,
    ) {
        val eventTime = SystemClock.uptimeMillis()
        val downTime = if (action == MotionEvent.ACTION_DOWN) eventTime else eventTime - TOUCH_GESTURE_MS
        val event =
            MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                view.width * normalizedX,
                view.height * normalizedY,
                0,
            )
        try {
            assertTrue("Touch surface rejected action $action", view.dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    private data class PairingQr(
        val uri: String,
        val proxyPort: Int,
        val cemuLive: Boolean,
    )

    private companion object {
        const val LOG_TAG = "TwinpadE2E"
        const val QR_IPC_HOST = "127.0.0.1"
        const val QR_IPC_PORT = 8423
        const val SERVER_PORT = 8420
        const val QR_IPC_TIMEOUT_MS = 3_000
        const val CONDITION_TIMEOUT_MS = 35_000L
        const val POLL_INTERVAL_MS = 50L
        const val LAYOUT_TIMEOUT_MS = 5_000L
        const val FRAME_MEASUREMENT_MS = 2_000L
        const val TOUCH_GESTURE_MS = 100L
        const val TOUCH_SETTLE_MS = 500L
    }
}
