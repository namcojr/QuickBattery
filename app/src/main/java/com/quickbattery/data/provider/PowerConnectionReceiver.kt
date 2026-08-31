package com.quickbattery.data.provider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Manifest-declared observer for power connect/disconnect events. These broadcasts are exempt from
 * Android's implicit-broadcast restrictions, so they still wake this receiver in the background even
 * with no service running. It records the charge/discharge boundary and lets the process go back to
 * sleep; everything else is recomputed the next time the app is opened.
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
    }
}