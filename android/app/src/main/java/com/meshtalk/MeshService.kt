package com.meshtalk

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps the node alive (scanning, relaying) while the app is in the background. */
class MeshService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        try {
            val n = ongoing("Starting…")
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(ID_SERVICE, n)
        } catch (e: Exception) {  // permissions revoked while we were restarting
            stopSelf()
            return
        }
        Mesh.start(this)
        scope.launch { Mesh.events.collect { notifyEvent(it) } }
        scope.launch {
            Mesh.state.map { s ->
                val now = System.currentTimeMillis()
                val near = s.nodes.count { now - it.lastDirect < Mesh.NEIGHBOR_MS }
                val all = s.nodes.count { now - it.lastSeen < Mesh.ACTIVE_MS }
                "$near nearby · $all in mesh · ${s.radio}"
            }.distinctUntilChanged().collect { post(ID_SERVICE, ongoing(it)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        Mesh.stop()
        super.onDestroy()
    }

    private fun openApp(peer: Int? = null, tab: Int? = null, code: Int = 0): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        peer?.let { i.putExtra(MainActivity.EXTRA_PEER, it) }
        tab?.let { i.putExtra(MainActivity.EXTRA_TAB, it) }
        return PendingIntent.getActivity(this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun ongoing(text: String): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, MeshService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("MeshTalk node active")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun notifyEvent(e: Mesh.Event) {
        when (e) {
            is Mesh.Event.Message -> {
                val m = e.m
                if (m.type == Proto.SOS) {
                    post(ID_SOS + (m.from and 0xFFF), NotificationCompat.Builder(this, CH_SOS)
                        .setSmallIcon(R.drawable.ic_stat)
                        .setContentTitle("SOS from ${Mesh.nameOf(m.from)}")
                        .setContentText(m.text)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(m.text))
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setColor(0xFFD32F2F.toInt())
                        .setAutoCancel(true)
                        .setContentIntent(openApp(peer = Proto.BCAST, code = 2))
                        .build())
                    return
                }
                if (Mesh.appVisible && Mesh.openChat == m.peer) return
                val title = if (m.peer == Proto.BCAST) "${Mesh.nameOf(m.from)} → Everyone" else Mesh.nameOf(m.from)
                post(ID_MSG + (m.peer and 0xFFFF), NotificationCompat.Builder(this, CH_MSG)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title)
                    .setContentText(m.text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(m.text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .setContentIntent(openApp(peer = m.peer, code = 0x100000 + m.peer))
                    .build())
            }
            is Mesh.Event.Request -> post(ID_REQ + (e.id and 0xFFFF), NotificationCompat.Builder(this, CH_REQ)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Friend request")
                .setContentText("${e.name} wants to connect")
                .setAutoCancel(true)
                .setContentIntent(openApp(tab = 2, code = 4))
                .build())
            is Mesh.Event.Info -> Unit  // shown in-app only
        }
    }

    private fun post(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(this).notify(id, n)
    }

    companion object {
        const val ACTION_STOP = "com.meshtalk.STOP"
        private const val CH_SERVICE = "service"
        private const val CH_MSG = "messages"
        private const val CH_REQ = "requests"
        private const val CH_SOS = "sos"
        private const val ID_SERVICE = 1
        private const val ID_SOS = 0x10000
        private const val ID_MSG = 0x20000
        private const val ID_REQ = 0x40000

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, MeshService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, MeshService::class.java).setAction(ACTION_STOP))

        private fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Mesh service", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_MSG, "Messages", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CH_REQ, "Friend requests", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CH_SOS, "SOS alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
                )
            })
        }
    }
}
