package com.quickbattery.data.provider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Manifest-declared observer for power connect/disconnect events.
 *
 * These two broadcasts are exempt from Android's implicit-broadcast restrictions, so they wake this
 * receiver with no service running - but only while the package is not in the stopped state, which
 * aggressive OEM builds put it into regularly. It is therefore a fast path, not a guarantee:
 * [BatteryMonitorService] and [BatteryEventRecorder.reconcile] cover the cases it misses.
 *
 * The work runs synchronously and every store it touches commits rather than applies, so the state
 * is durable by the time onReceive returns. Deferring it to a background thread would risk the
 * process being frozen mid-write, which is the one failure this receiver cannot afford.
 */
class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        val now = System.currentTimeMillis()
        when (intent?.action) {
            Intent.ACTION_POWER_CONNECTED -> BatteryEventRecorder.onPowerConnected(context, now)
            Intent.ACTION_POWER_DISCONNECTED -> BatteryEventRecorder.onPowerDisconnected(context, now)
            else -> return
        }

        // Being woken at all proves the process is alive right now, which is the best chance to
        // get the monitor running again if the system had killed it.
        BatteryMonitorService.start(context)
        MonitorWatchdog.schedule(context)
    }
}
