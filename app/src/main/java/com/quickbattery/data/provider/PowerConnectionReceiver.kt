package com.quickbattery.data.provider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.quickbattery.domain.model.BatteryStatus

class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        val now = System.currentTimeMillis()
        when (intent?.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                BatterySessionStore.markPowerConnected(context)
                recordBoundarySample(context, now, BatteryStatus.Charging)
            }

            Intent.ACTION_POWER_DISCONNECTED -> {
                BatterySessionStore.markPowerDisconnected(context, now)
                recordBoundarySample(context, now, BatteryStatus.Discharging)
            }
        }
    }

    // Persist a definitive charge/discharge boundary into the level history so charge-cycle
    // detection stays reliable even when the app process is never launched between charges.
    private fun recordBoundarySample(
        context: Context,
        timestampMillis: Long,
        status: BatteryStatus,
    ) {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) {
            return
        }

        val levelPercent = (level * 100f / scale.toFloat()).toInt().coerceIn(0, 100)
        BatteryLevelHistoryStore.appendSample(
            context = context,
            timestampMillis = timestampMillis,
            levelPercent = levelPercent,
            status = status,
        )
    }
}