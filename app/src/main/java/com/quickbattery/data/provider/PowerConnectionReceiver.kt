package com.quickbattery.data.provider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Manifest-declared backup for power connect/disconnect events. The always-on
 * [BatteryMonitorService] is the primary observer; this receiver adds redundancy in case the
 * service is momentarily not running (e.g. right after an OEM kill, before it is restarted).
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
        }
        // Ensure the monitor is running again as soon as any power event is observed.
        BatteryMonitorService.start(context)
    }
}