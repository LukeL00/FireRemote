package com.luke.fireremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.RemoteViews
import com.luke.fireremote.FireTvClient.Keys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the Fire Stick connection open in the background and shows a
 * notification with remote buttons - usable from the lock screen and the pull-down shade.
 */
class RemoteService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        val app = application as RemoteApp
        if (app.savedIp.isNotBlank()) scope.launch { app.client.ensureConnected(app.savedIp) }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "remote"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.luke.fireremote.STOP"

        fun start(context: Context) {
            val i = Intent(context, RemoteService::class.java)
            runCatching { context.startForegroundService(i) }
        }

        private fun buildNotification(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Remote controls", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Fire TV remote buttons on your lock screen and notification shade"
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        setShowBadge(false)
                    }
                )
            }

            val small = RemoteViews(context.packageName, R.layout.notif_small).apply {
                bindKeys(context, R.id.btn_back, R.id.btn_left, R.id.btn_up, R.id.btn_ok, R.id.btn_down, R.id.btn_right, R.id.btn_play)
            }
            val big = RemoteViews(context.packageName, R.layout.notif_big).apply {
                bindKeys(context, R.id.btn_back, R.id.btn_up, R.id.btn_home, R.id.btn_left, R.id.btn_ok, R.id.btn_right, R.id.btn_play, R.id.btn_down)
                setOnClickPendingIntent(
                    R.id.btn_close,
                    PendingIntent.getService(
                        context, 99,
                        Intent(context, RemoteService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            }
            val openApp = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_remote)
                .setContentTitle("Fire Remote")
                .setStyle(Notification.DecoratedCustomViewStyle())
                .setCustomContentView(small)
                .setCustomBigContentView(big)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .build()
        }

        private val buttons = mapOf(
            R.id.btn_up to Keys.UP,
            R.id.btn_down to Keys.DOWN,
            R.id.btn_left to Keys.LEFT,
            R.id.btn_right to Keys.RIGHT,
            R.id.btn_ok to Keys.OK,
            R.id.btn_back to Keys.BACK,
            R.id.btn_home to Keys.HOME,
            R.id.btn_play to Keys.PLAY_PAUSE,
        )

        /** Wires every button present in this layout to a key press. */
        private fun RemoteViews.bindKeys(context: Context, vararg ids: Int) {
            ids.forEach { viewId ->
                val code = buttons.getValue(viewId)
                val intent = Intent(context, KeyReceiver::class.java)
                    .setAction("com.luke.fireremote.KEY_$code")
                    .putExtra(KeyReceiver.EXTRA_CODE, code)
                val pi = PendingIntent.getBroadcast(
                    context, code, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                setOnClickPendingIntent(viewId, pi)
            }
        }
    }
}

/** Receives notification button taps and sends the key to the Fire Stick. */
class KeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getIntExtra(EXTRA_CODE, -1)
        if (code < 0) return
        val app = context.applicationContext as RemoteApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!app.client.isConnected && app.savedIp.isNotBlank()) app.client.ensureConnected(app.savedIp)
                app.client.key(code)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_CODE = "code"
    }
}
