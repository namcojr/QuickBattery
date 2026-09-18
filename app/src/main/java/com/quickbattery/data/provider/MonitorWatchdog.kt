package com.quickbattery.data.provider

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Last line of defence against the monitor being torn down.
 *
 * ColorOS will kill even a foreground service when its background app management decides to, and
 * once the package is in the stopped state no broadcast - including the power ones - reaches the
 * app again on its own. A self-rescheduling alarm survives that: it restarts the process, repairs
 * any boundary that was missed while it was down, and brings the service back.
 *
 * The alarm is inexact and fires at most a few times an hour, so it costs effectively nothing; it
 * exists only so a killed monitor recovers by itself instead of waiting for the user to notice.
 */
internal object MonitorWatchdog {

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val triggerAtMillis = System.currentTimeMillis() + INTERVAL_MILLIS

        runCatching {
            // setAndAllowWhileIdle still fires in Doze, which is exactly when a killed monitor
            // would otherwise stay dead for hours.
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent(appContext),
            )
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MonitorWatchdogReceiver::class.java)
            .setAction(ACTION_WATCHDOG)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val ACTION_WATCHDOG = "com.quickbattery.action.MONITOR_WATCHDOG"
    private const val REQUEST_CODE = 2001
    private const val INTERVAL_MILLIS = 15L * 60L * 1000L
}

/** Alarm target: repair whatever was missed, restart the monitor, and arm the next check. */
class MonitorWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        BatteryEventRecorder.reconcile(context)
        BatteryMonitorService.start(context)
        MonitorWatchdog.schedule(context)
    }
}
