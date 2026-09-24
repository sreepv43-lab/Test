package io.github.sreepv43.streamhub.download

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
import io.github.sreepv43.streamhub.MainActivity
import io.github.sreepv43.streamhub.R
import io.github.sreepv43.streamhub.StreamHubApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** Keeps the app alive and shows progress while downloads are running. */
class DownloadService : Service() {

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
            buildNotification("Preparing downloads…", 0, indeterminate = true),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        acquireLocks()
        if (!observing) {
            observing = true
            observe()
        }
        return START_NOT_STICKY
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observe() {
        val downloader = (application as StreamHubApp).container.downloader
        scope.launch {
            combine(downloader.activeCount, downloader.items) { active, items -> active to items }
                .sample(1_000)
                .collect { (active, items) ->
                    if (active == 0) {
                        stopSelf()
                        return@collect
                    }
                    val running = items.filter { it.status == DownloadItem.Status.RUNNING }
                    val total = running.sumOf { it.totalBytes.coerceAtLeast(0) }
                    val done = running.sumOf { it.downloadedBytes }
                    val title = if (running.size == 1) running.first().title else "Downloading ${running.size} videos"
                    val percent = if (total > 0) (done * 100 / total).toInt() else 0
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(title, percent, indeterminate = total <= 0))
                }
        }
    }

    /** Android 15 limits dataSync services to 6h per day; pause instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as StreamHubApp).container.downloader.pauseAll()
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
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamHub:downloads")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "StreamHub:downloads")
                ?.apply { setReferenceCounted(false); acquire() }
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
            0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(if (indeterminate) "Downloading…" else "$percent%")
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 42
    }
}
