package com.akira.simpleplayer.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.akira.simpleplayer.MainActivity
import com.akira.simpleplayer.R
import com.akira.simpleplayer.core.Core
import com.akira.simpleplayer.core.CoreBoot
import com.akira.simpleplayer.core.Lib
import com.akira.simpleplayer.playback.Playback
import com.akira.simpleplayer.ui.tr
import org.json.JSONObject

/**
 * Keeps a resync alive while the app is in the background.
 *
 * The resync itself runs on the core's own threads; without a foreground service Android
 * freezes or kills the process as soon as the app leaves the screen, and the resync stops
 * half way. This service holds a progress notification, a CPU wake lock and a Wi-Fi lock
 * until the core reports it is done, then hands the new library to the player.
 */
class SyncService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CoreBoot.ensure(this)
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, tr("曲庫同步", "Library sync"), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                Core.syncCancel()
                finish(tr("已取消同步", "Sync cancelled"), null)
                return START_NOT_STICKY
            }
            else -> goForeground(progress(tr("同步中", "Syncing"), 0, 0))
        }
        holdLocks()
        if (!watching) {
            watching = true
            handler.post(poll)
        }
        // if the process is killed anyway, the half-done resync is simply dropped (the old
        // library stays intact): no point in being restarted
        return START_NOT_STICKY
    }

    private val poll = object : Runnable {
        override fun run() {
            val o = runCatching { JSONObject(Core.syncState()) }.getOrDefault(JSONObject())
            if (o.optBoolean("running")) {
                notify(progress(o.optString("stage"), o.optInt("done"), o.optInt("total")))
                handler.postDelayed(this, 500)
                return
            }
            // the library was replaced: the player plans its next song with the new one
            if (Lib.refreshIfChanged()) Playback.libraryChanged()
            val error = o.optString("error")
            finish(
                if (error.isBlank()) tr("同步完成：${Lib.size} 首", "Sync done: ${Lib.size} songs")
                else tr("同步結束", "Sync finished"),
                error.ifBlank { null }
            )
        }
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, n)
        }
    }

    private fun notify(n: Notification) =
        getSystemService(NotificationManager::class.java).notify(ID, n)

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun progress(stage: String, done: Int, total: Int): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, SyncService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.simple_player_icon)
            .setContentTitle(tr("正在同步曲庫", "Syncing the library"))
            .setContentText(if (total > 0) "$stage  $done / $total" else stage)
            .setProgress(total, done, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, tr("取消", "Cancel"), cancel)
            .build()
    }

    private fun finish(title: String, detail: String?) {
        handler.removeCallbacks(poll)
        watching = false
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // a short note that stays after the service is gone (it can be swiped away)
        notify(
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.simple_player_icon)
                .setContentTitle(title)
                .apply { if (detail != null) setContentText(detail).setStyle(NotificationCompat.BigTextStyle().bigText(detail)) }
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build()
        )
        stopSelf()
    }

    private fun holdLocks() {
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SimplePlayer:sync")
                .apply { setReferenceCounted(false); acquire(60 * 60 * 1000L) }
        }
        if (wifi == null) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(mode, "SimplePlayer:sync")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        wake = null
        wifi = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "sync"
        private const val ID = 7301
        private const val ACTION_START = "com.akira.simpleplayer.sync.START"
        private const val ACTION_CANCEL = "com.akira.simpleplayer.sync.CANCEL"

        /** Call right after Core.syncStart: keeps the process alive until it is done. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, SyncService::class.java).setAction(ACTION_START)
            )
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, SyncService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
