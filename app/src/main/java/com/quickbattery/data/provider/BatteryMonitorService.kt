package com.quickbattery.data.provider

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.quickbattery.MainActivity
import com.quickbattery.R
import com.quickbattery.domain.model.BatteryStatus

/**
 * Always-on foreground service that keeps QuickBattery aware of charge/discharge boundaries even
 * while the UI is closed. Manifest-declared receivers alone are unreliable on OEM builds that
 * aggressively freeze background apps, so a foreground service with a runtime-registered receiver
 * is the only Android-sanctioned way to observe battery events continuously.
 *
 * The service registers for [Intent.ACTION_BATTERY_CHANGED] (only deliverable to runtime
 * receivers) plus the power connect/disconnect actions, funnelling every event through
 * [BatteryEventRecorder] so "time since last charge" starts counting the instant a charger is
 * unplugged instead of only when the app is next opened.
 */
class BatteryMonitorService : Service() {

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val now = System.currentTimeMillis()
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> BatteryEventRecorder.onPowerConnected(context, now)
                Intent.ACTION_POWER_DISCONNECTED -> BatteryEventRecorder.onPowerDisconnected(context, now)
                Intent.ACTION_BATTERY_CHANGED -> BatteryEventRecorder.onBatteryChanged(context, intent, now)
            }
            updateNotification(intent)
        }
    }

    private var lastNotificationText: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(buildNotification(statusText = null))
        registerBatteryReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert foreground state on redelivery so OEM restarts keep the monitor alive.
        startForegroundCompat(buildNotification(statusText = null))
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(batteryReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerBatteryReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        // The sticky ACTION_BATTERY_CHANGED is returned immediately on registration, seeding an
        // up-to-date sample the moment monitoring starts.
        val sticky = ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        BatteryEventRecorder.onBatteryChanged(this, sticky)
        updateNotification(sticky)
    }

    private fun updateNotification(batteryIntent: Intent?) {
        val statusText = describeStatus(batteryIntent) ?: return
        // ACTION_BATTERY_CHANGED fires on temp/voltage changes too; only re-post when the visible
        // text actually changes to avoid needless notification churn.
        if (statusText == lastNotificationText) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
        lastNotificationText = statusText
    }

    private fun describeStatus(batteryIntent: Intent?): String? {
        val level = BatteryEventRecorder.readLevelPercent(batteryIntent) ?: return null
        val status = BatteryEventRecorder.readStatus(batteryIntent)
        val state = when (status) {
            BatteryStatus.Charging, BatteryStatus.Full -> "Charging"
            else -> "On battery"
        }
        return "$state · $level%"
    }

    private fun buildNotification(statusText: String?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("QuickBattery monitor")
            .setContentText(statusText ?: "Tracking time since last charge")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Battery monitoring",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Keeps time-since-last-charge accurate while the app is closed."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "battery_monitor"
        private const val NOTIFICATION_ID = 1001

        /** Starts the monitor as a foreground service; safe to call repeatedly. */
        fun start(context: Context) {
            val intent = Intent(context, BatteryMonitorService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
