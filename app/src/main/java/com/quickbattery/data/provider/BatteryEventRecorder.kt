package com.quickbattery.data.provider

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.quickbattery.domain.model.BatteryStatus

/**
 * Single source of truth for turning raw battery broadcasts into persisted state.
 *
 * Both the always-on [BatteryMonitorService] and the manifest-declared
 * [PowerConnectionReceiver] funnel their events through here so charge/discharge
 * boundaries are recorded identically regardless of which component observed them.
 */
internal object BatteryEventRecorder {

    fun onPowerConnected(
        context: Context,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        BatterySessionStore.markPowerConnected(context)
        recordBoundarySample(context, timestampMillis, BatteryStatus.Charging)
    }

    fun onPowerDisconnected(
        context: Context,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        BatterySessionStore.markPowerDisconnected(context, timestampMillis)
        recordBoundarySample(context, timestampMillis, BatteryStatus.Discharging)
    }

    /**
     * Handles an [Intent.ACTION_BATTERY_CHANGED] broadcast. Keeps the session store and level
     * history continuously fed while the monitor service is alive, so "time since last charge"
     * and the discharge-trend regression stay accurate even if the app UI is never opened.
     */
    fun onBatteryChanged(
        context: Context,
        batteryIntent: Intent?,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        val status = readStatus(batteryIntent) ?: return
        val levelPercent = readLevelPercent(batteryIntent) ?: return

        BatterySessionStore.updateFromSnapshot(
            context = context,
            status = status,
            timestampMillis = timestampMillis,
        )
        BatteryLevelHistoryStore.appendSampleIfChanged(
            context = context,
            timestampMillis = timestampMillis,
            levelPercent = levelPercent,
            status = status,
        )
    }

    private fun recordBoundarySample(
        context: Context,
        timestampMillis: Long,
        status: BatteryStatus,
    ) {
        val levelPercent = readLevelPercent(readBatteryIntent(context)) ?: return
        BatteryLevelHistoryStore.appendSample(
            context = context,
            timestampMillis = timestampMillis,
            levelPercent = levelPercent,
            status = status,
        )
    }

    fun readBatteryIntent(context: Context): Intent? {
        return context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    fun readLevelPercent(batteryIntent: Intent?): Int? {
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) {
            return null
        }
        return (level * 100f / scale.toFloat()).toInt().coerceIn(0, 100)
    }

    fun readStatus(batteryIntent: Intent?): BatteryStatus? {
        val code = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return when (code) {
            BatteryManager.BATTERY_STATUS_CHARGING -> BatteryStatus.Charging
            BatteryManager.BATTERY_STATUS_DISCHARGING -> BatteryStatus.Discharging
            BatteryManager.BATTERY_STATUS_FULL -> BatteryStatus.Full
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> BatteryStatus.NotCharging
            else -> null
        }
    }
}
