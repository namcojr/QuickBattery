package com.quickbattery.data.provider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        when (intent?.action) {
            Intent.ACTION_POWER_CONNECTED -> BatterySessionStore.markPowerConnected(context)
            Intent.ACTION_POWER_DISCONNECTED -> BatterySessionStore.markPowerDisconnected(context)
        }
    }
}