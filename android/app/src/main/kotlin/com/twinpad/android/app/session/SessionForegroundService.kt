package com.twinpad.android.app.session

import android.Manifest
import android.app.Activity
import android.app.ForegroundServiceTypeException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
 * terminar la sesión o fallar. CR-121: el texto refleja si hay transmisión
 * (V1/V2 de SPEC-003) o solo sesión conectada; la Activity la actualiza al
 * cambiar el estado del stream.
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
        // CR-121: la Activity re-arranca el servicio solo con el modo vigente.
        streaming = intent?.getBooleanExtra(EXTRA_STREAMING, streaming) ?: streaming
        goForeground()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** CR-121: `true` mientras el stream entrega frames (V1/V2). */
    private var streaming = false

    private fun goForeground() {
        val notification = buildNotification(this, streaming)
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

    companion object {
        /** Id del canal de sesión (Android 8+: crear antes de notificar). */
        private const val CHANNEL_ID = "twinpad_session"

        /** Notificación única del servicio. */
        private const val NOTIFICATION_ID = 42

        /** Arranca el servicio en primer plano (idempotente; llamado en U4). */
        fun start(
            context: Context,
            streaming: Boolean = streamingNow,
        ) {
            ensureChannel(context)
            // CR-121: la Activity es quien puede pedir el permiso; desde otro
            // Context se confía en el permiso ya concedido.
            (context as? Activity)?.let(::requestNotificationPermission)
            val intent =
                Intent(context, SessionForegroundService::class.java)
                    .putExtra(EXTRA_STREAMING, streaming)
            context.startForegroundService(intent)
        }

        /**
         * CR-121: refresca el texto de la notificación sin volver a arrancar el
         * servicio (`startForegroundService` desde segundo plano está
         * restringido en Android 12+, y aquí el servicio ya está en primer plano).
         */
        fun update(
            context: Context,
            streaming: Boolean,
        ) {
            streamingNow = streaming
            ensureChannel(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(context, streaming))
        }

        /** Ya se pidió el permiso en este proceso (no se repite el diálogo). */
        private var permissionAsked = false

        /**
         * CR-121: Android 13+ no muestra la notificación del servicio sin
         * `POST_NOTIFICATIONS`. Se pide al entrar en sesión; negarlo solo deja
         * la sesión sin notificación (el servicio sigue vivo).
         */
        private fun requestNotificationPermission(activity: Activity) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || permissionAsked) return
            val granted =
                ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (granted) return
            permissionAsked = true
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                PERMISSION_REQUEST,
            )
        }

        /** Código de la petición del permiso de notificaciones. */
        private const val PERMISSION_REQUEST = 43

        /** Último modo conocido: `start` lo conserva si la Activity no lo pasa. */
        private var streamingNow = false

        /** CR-121: extra con el modo de transmisión vigente. */
        private const val EXTRA_STREAMING = "streaming"

        /** Detiene el servicio (fin de sesión o failure). */
        fun stop(context: Context) {
            streamingNow = false
            context.stopService(Intent(context, SessionForegroundService::class.java))
        }

        /** CR-121: notificación de la sesión; el texto depende de si el
         * stream entrega frames (V1/V2) o solo hay sesión conectada. */
        private fun buildNotification(
            context: Context,
            streaming: Boolean,
        ): Notification {
            val stringId =
                if (streaming) {
                    R.string.session_notification_text_streaming
                } else {
                    R.string.session_notification_text
                }
            val text = context.getString(stringId)
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.session_notification_title))
                .setContentText(text)
                .setContentIntent(pairingIntent(context))
                .setOngoing(true)
                .build()
        }

        /** Tap en la notificación → vuelve a la Activity (la señal existe solo
         * mientras la sesión vive; no abre una Activity extra). */
        private fun pairingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, PairingActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

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
