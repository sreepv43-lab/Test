package io.github.sreepv43.soundhub.service

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
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.sreepv43.soundhub.MainActivity
import io.github.sreepv43.soundhub.R
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.slsk.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** Keeps the app alive and shows progress while songs are downloading. */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Waiting for downloads…", 0, indeterminate = true),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        acquireLocks()
        if (!observing) {
            observing = true
            observe()
        }
        return START_NOT_STICKY
    }

    @OptIn(FlowPreview::class)
    private fun observe() {
        scope.launch {
            container.client.transfers.sample(1_000).collect { transfers ->
                val active = transfers.filter { it.active }
                if (active.isEmpty()) {
                    stopSelf()
                    return@collect
                }
                val running = active.filter { it.status == TransferStatus.TRANSFERRING }
                val total = running.sumOf { it.size.coerceAtLeast(0) }
                val done = running.sumOf { it.bytes }
                val title = when {
                    running.isEmpty() -> "${active.size} song${if (active.size == 1) "" else "s"} waiting in queues"
                    running.size == 1 -> running.first().filename.substringAfterLast('\\')
                    else -> "Downloading ${running.size} songs"
                }
                val percent = if (total > 0) (done * 100 / total).toInt() else 0
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(title, percent, indeterminate = total <= 0))
            }
        }
    }

    /** Android 15 limits dataSync services to 6 hours a day. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoundHub:downloads")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SoundHub:downloads")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, percent: Int, indeterminate: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (indeterminate) "Soulseek" else "$percent%")
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 7

        /** Call from the foreground (e.g. when the user starts a download). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
        }
    }
}
