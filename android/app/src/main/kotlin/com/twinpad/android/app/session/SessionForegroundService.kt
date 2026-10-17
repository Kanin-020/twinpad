package com.twinpad.android.app.session

import android.app.ForegroundServiceTypeException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.twinpad.android.app.R
import com.twinpad.android.app.ui.PairingActivity

/**
 * Servicio en primer plano de la sesión (pedido del owner: al cambiar de
 * app el sistema no mata el proceso y la transmisión sigue).
 *
 * Contrato de Android: después de `startForegroundService` el servicio
 * DEBE llamar `startForeground()` enseguida (crash
 * `ForegroundServiceDidNotStartInTime` si no). Se llama en `onCreate` Y
 * `onStartCommand` para cubrir tanto el primer arranque como los re-starts
 * con la notificación vigente. `startForegroundSdk` en targetSdk 35 exige
 * el tipo declarado (mediaPlayback).
 *
 * Ciclo: la Activity lo arranca al entrar a Linked (U4) y lo detiene al
 * terminar la sesión o fallar.
 */
class SessionForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        goForeground()
    }

    /** `startForegroundService` llega aquí tanto al arranque como en
     * re-invocaciones (la Activity puede volver a pedirlo): renueva el
     * estado "foreground" inmediatamente (requisito del sistema). */
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        goForeground()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun goForeground() {
        val notification = buildNotification(this)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } catch (e: ForegroundServiceTypeException) {
                // El tipo declarado en el manifest no está autorizado (FOG,
                // permiso del owner en ajustes Android 14+): bajar a dawn.
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(context: Context): Notification =
        Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.touch_button)
            .setContentTitle(context.getString(R.string.session_notification_title))
            .setContentText(context.getString(R.string.session_notification_text))
            .setContentIntent(pairingIntent(context))
            .setOngoing(true)
            .build()

    /** Tap en la notificación → vuelve a la Activity (la señal existe solo
     * mientras la sesión vive; no abre una Activity extra). */
    private fun pairingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, PairingActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        /** Id del canal de sesión (Android 8+: crear antes de notificar). */
        private const val CHANNEL_ID = "twinpad_session"

        /** Notificación única del servicio. */
        private const val NOTIFICATION_ID = 42

        /** Arranca el servicio en primer plano (idempotente; llamado en U4). */
        fun start(context: Context) {
            ensureChannel(context)
            context.startForegroundService(Intent(context, SessionForegroundService::class.java))
        }

        /** Detiene el servicio (fin de sesión o failure). */
        fun stop(context: Context) {
            context.stopService(Intent(context, SessionForegroundService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.session_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            manager.createNotificationChannel(channel)
        }
    }
}
