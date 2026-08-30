package com.pugetflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Foreground service that keeps the river overlay live: it polls USGS on an
 * interval, pushes fresh readings into OsmAnd, and exposes a "Refresh now"
 * action in its ongoing notification (the button that triggers a query on demand).
 */
class RiverService : Service() {

    companion object {
        const val ACTION_START = "com.pugetflow.START"
        const val ACTION_REFRESH = "com.pugetflow.REFRESH"
        const val ACTION_STOP = "com.pugetflow.STOP"

        private const val CHANNEL_ID = "river_updates"
        private const val NOTIF_ID = 42
        private const val REFRESH_INTERVAL_MS = 5 * 60_000L // 5 minutes

        /** Simple status pipe the Activity can observe while it's in the foreground. */
        @Volatile var lastStatus: String = "Idle"
        @Volatile var statusListener: ((String) -> Unit)? = null
        private fun setStatus(s: String) {
            lastStatus = s
            statusListener?.invoke(s)
        }
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var bridge: OsmAndBridge
    private var running = false

    private val periodicRefresh = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        bridge = OsmAndBridge(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // If we were started via startForegroundService we must call
                // startForeground before stopping, or Android throws.
                ensureForeground()
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                ensureForeground()
                refresh()
            }
            else -> { // ACTION_START or null
                if (!running) {
                    running = true
                    ensureForeground()
                    if (bridge.osmandPackage() == null) {
                        setStatus("OsmAnd is not installed.")
                    } else {
                        bridge.bind()
                    }
                    main.post(periodicRefresh) // fires an immediate refresh, then every interval
                }
            }
        }
        return START_STICKY
    }

    private fun refresh() {
        setStatus("Refreshing…")
        io.execute {
            try {
                val readings = UsgsClient.fetch(Sites.SITE_IDS)
                main.post {
                    if (!bridge.isConnected) bridge.bind()
                    bridge.publish(readings)
                    val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                    val lines = readings.joinToString("\n") { r ->
                        val flow = r.flowCfs?.let { "${fmt(it)} cfs" } ?: "—"
                        val temp = r.tempC?.let { " · ${fmt(it)}°C" } ?: ""
                        "• ${r.name}: $flow$temp"
                    }
                    setStatus("Updated $stamp — ${readings.size} sites\n$lines")
                    updateNotification("${readings.size} sites • updated $stamp")
                }
            } catch (e: Exception) {
                main.post {
                    setStatus("Update failed: ${e.message}")
                    updateNotification("Update failed — will retry")
                }
            }
        }
    }

    private fun stopEverything() {
        main.removeCallbacks(periodicRefresh)
        try {
            bridge.removeLayer()
        } catch (_: Exception) {
        }
        bridge.unbind()
        running = false
        setStatus("Stopped.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- notification ---

    private fun ensureForeground() {
        val notif = buildNotification(lastStatus.substringBefore("\n"))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val refreshPi = servicePi(ACTION_REFRESH, 1)
        val stopPi = servicePi(ACTION_STOP, 2)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PugetFlow — river gauges")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_water)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Refresh now", refreshPi)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    private fun servicePi(action: String, code: Int): PendingIntent {
        val i = Intent(this, RiverService::class.java).apply { this.action = action }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, code, i, flags)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun fmt(v: Double): String =
        if (v >= 100 || v == Math.floor(v)) v.toLong().toString() else String.format("%.1f", v)

    override fun onDestroy() {
        main.removeCallbacks(periodicRefresh)
        io.shutdownNow()
        statusListener = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
