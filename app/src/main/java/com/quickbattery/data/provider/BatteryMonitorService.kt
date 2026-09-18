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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.quickbattery.MainActivity
import com.quickbattery.R
import com.quickbattery.domain.model.BatteryStatus

/**
 * Always-on foreground service that keeps QuickBattery aware of charge/discharge boundaries while
 * the UI is closed.
 *
 * A manifest-declared receiver is not sufficient on ColorOS and similar OEM builds. Once the system
 * puts the package into the stopped state - which its background app management does routinely -
 * broadcasts are filtered out by FLAG_EXCLUDE_STOPPED_PACKAGES and the receiver is never invoked
 * again until the user reopens the app by hand. A foreground service keeps the process out of that
 * state and is the only sanctioned way to observe battery events continuously.
 *
 * Running here also buys access to [Intent.ACTION_BATTERY_CHANGED], which the platform only ever
 * delivers to runtime-registered receivers. That gives a second, independent way to notice the
 * charger being plugged or unplugged when the dedicated power broadcast goes missing.
 *
 * While a charger is attached (or diagnostic logging is on) it also polls the fuel gauge every few
 * seconds on a background thread, feeding [ChargingMeter] the dense samples it needs to smooth the
 * live current and calibrate it against the charge counter. Polling stops as soon as neither holds,
 * so it costs nothing on battery.
 */
class BatteryMonitorService : Service() {

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val now = System.currentTimeMillis()
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> BatteryEventRecorder.onPowerConnected(context, now)
                Intent.ACTION_POWER_DISCONNECTED -> BatteryEventRecorder.onPowerDisconnected(context, now)
                Intent.ACTION_BATTERY_CHANGED -> {
                    BatteryEventRecorder.onBatteryChanged(context, intent, now)
                    onBatteryIntent(intent, source = SOURCE_BROADCAST)
                }
            }
            updateNotification(intent)
        }
    }

    private var lastNotificationText: String? = null

    private lateinit var samplerThread: HandlerThread
    private lateinit var samplerHandler: Handler

    @Volatile
    private var latestBatteryIntent: Intent? = null

    // Only touched on the sampler thread.
    private var samplerRunning = false

    private val sampleTick = object : Runnable {
        override fun run() {
            val context = this@BatteryMonitorService
            val logging = BatteryDiagnosticsLog.isEnabled(context)
            val plugged = BatteryEventRecorder.readPlugged(latestBatteryIntent) == true
            if (!plugged && !logging) {
                samplerRunning = false
                return
            }
            // The broadcast only carries level/voltage/temperature; everything else is read live.
            recordSample(latestBatteryIntent, SOURCE_TICK)
            samplerHandler.postDelayed(this, if (logging) LOGGING_SAMPLE_INTERVAL_MILLIS else CHARGING_SAMPLE_INTERVAL_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        samplerThread = HandlerThread("battery-sampler").apply { start() }
        samplerHandler = Handler(samplerThread.looper)
        createNotificationChannel()
        startForegroundCompat(buildNotification(statusText = null))
        // Whatever happened while the service was down is repaired before live events resume.
        BatteryEventRecorder.reconcile(this)
        registerBatteryReceiver()
        MonitorWatchdog.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert foreground state on redelivery so OEM restarts keep the monitor alive.
        startForegroundCompat(buildNotification(lastNotificationText))
        MonitorWatchdog.schedule(this)
        // Also the hook for diagnostic logging being switched on: it restarts the service.
        ensureSampling()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must not end monitoring; the watchdog brings it straight back if
        // the platform tears the service down with the task.
        MonitorWatchdog.schedule(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(batteryReceiver) }
        samplerHandler.removeCallbacksAndMessages(null)
        samplerThread.quitSafely()
        MonitorWatchdog.schedule(this)
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
        onBatteryIntent(sticky, source = SOURCE_BROADCAST)
        updateNotification(sticky)
    }

    private fun onBatteryIntent(intent: Intent?, source: String) {
        if (intent == null) return
        latestBatteryIntent = intent
        samplerHandler.post {
            BatteryDiagnosticsLog.recordBroadcast(this, intent)
            recordSample(intent, source)
        }
        ensureSampling()
    }

    private fun ensureSampling() {
        samplerHandler.post {
            if (!samplerRunning) {
                samplerRunning = true
                sampleTick.run()
            }
        }
    }

    private fun recordSample(intent: Intent?, source: String) {
        val batteryIntent = intent ?: BatteryEventRecorder.readBatteryIntent(this)
        val sample = ChargingMeter.sample(this, batteryIntent, source)
        if (BatteryDiagnosticsLog.isEnabled(this)) {
            BatteryDiagnosticsLog.recordSample(this, sample, ChargingMeter.resolve(this))
        }
    }

    private fun updateNotification(batteryIntent: Intent?) {
        val statusText = describeStatus(batteryIntent) ?: return
        // ACTION_BATTERY_CHANGED fires on temp/voltage changes too; only re-post when the visible
        // text actually changes to avoid needless notification churn.
        if (statusText == lastNotificationText) {
            return
        }
        lastNotificationText = statusText
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(statusText)) }
    }

    private fun describeStatus(batteryIntent: Intent?): String? {
        val level = BatteryEventRecorder.readLevelPercent(batteryIntent) ?: return null
        val plugged = BatteryEventRecorder.readPlugged(batteryIntent)
        val status = BatteryEventRecorder.readStatus(batteryIntent)
        val state = when {
            plugged == true -> "Charging"
            plugged == false -> "On battery"
            status == BatteryStatus.Charging || status == BatteryStatus.Full -> "Charging"
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
        runCatching {
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
        private const val SOURCE_BROADCAST = "broadcast"
        private const val SOURCE_TICK = "tick"
        private const val CHARGING_SAMPLE_INTERVAL_MILLIS = 5_000L
        private const val LOGGING_SAMPLE_INTERVAL_MILLIS = 2_000L

        /**
         * Starts the monitor as a foreground service; safe to call repeatedly.
         *
         * Every start is wrapped: a background start can be refused outright (for example while the
         * app sits in a restricted standby bucket), and a monitor that cannot start must not take
         * the caller - an activity, or a receiver recording a real power event - down with it.
         */
        fun start(context: Context) {
            val intent = Intent(context.applicationContext, BatteryMonitorService::class.java)
            runCatching { context.applicationContext.startForegroundService(intent) }
        }
    }
}
