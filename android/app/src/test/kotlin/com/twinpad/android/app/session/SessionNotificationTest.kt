package com.twinpad.android.app.session

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.twinpad.android.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * TEST-0214 — verifica CR-121: la notificación del servicio de sesión (la que
 * se ve al cambiar de app mientras hay transmisión) cambia de texto según el
 * estado del stream y usa el canal de sesión.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionNotificationTest {
    /** Id de la notificación única del servicio (CR-121). */
    private val notificationId = 42

    @Test
    fun `notification reports active transmission`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        SessionForegroundService.update(context, streaming = true)

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(manager).getNotification(notificationId)

        assertEquals(
            context.getString(R.string.session_notification_text_streaming),
            shadowOf(notification).contentText.toString(),
        )
    }

    @Test
    fun `notification falls back to connected session text`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        SessionForegroundService.update(context, streaming = false)

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(manager).getNotification(notificationId)

        assertEquals(
            context.getString(R.string.session_notification_text),
            shadowOf(notification).contentText.toString(),
        )
    }
}
