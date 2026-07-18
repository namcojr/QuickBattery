package com.quickbattery.data.provider

import android.content.Context
import com.quickbattery.domain.model.BatteryStatus

internal object BatterySessionStore {

    fun markPowerDisconnected(
        context: Context,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        prefs(context)
            .edit()
            .putLong(KEY_DISCHARGING_STARTED_AT_MILLIS, timestampMillis)
            .putString(KEY_LAST_STATUS, BatteryStatus.Discharging.name)
            .apply()
    }

    fun markPowerConnected(context: Context) {
        prefs(context)
            .edit()
            .remove(KEY_DISCHARGING_STARTED_AT_MILLIS)
            .putString(KEY_LAST_STATUS, BatteryStatus.Charging.name)
            .apply()
    }

    fun updateFromSnapshot(
        context: Context,
        status: BatteryStatus,
        timestampMillis: Long,
    ) {
        val previousStatus = readLastStatus(context)

        when {
            status.isChargingState() -> {
                markPowerConnected(context)
            }

            status.isDischargingState() -> {
                val editor = prefs(context)
                    .edit()
                    .putString(KEY_LAST_STATUS, status.name)

                // Only trust snapshot timestamps when a charging -> discharging transition is observed.
                if (previousStatus?.isChargingState() == true) {
                    editor.putLong(KEY_DISCHARGING_STARTED_AT_MILLIS, timestampMillis)
                }

                editor.apply()
            }

            else -> {
                prefs(context)
                    .edit()
                    .putString(KEY_LAST_STATUS, status.name)
                    .apply()
            }
        }
    }

    fun getLastDischargingStartMillis(context: Context): Long? {
        val value = prefs(context).getLong(KEY_DISCHARGING_STARTED_AT_MILLIS, -1L)
        return value.takeIf { it > 0L }
    }

    private fun readLastStatus(context: Context): BatteryStatus? {
        val rawStatus = prefs(context).getString(KEY_LAST_STATUS, null) ?: return null
        return runCatching { BatteryStatus.valueOf(rawStatus) }.getOrNull()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun BatteryStatus.isChargingState(): Boolean {
        return this == BatteryStatus.Charging || this == BatteryStatus.Full
    }

    private fun BatteryStatus.isDischargingState(): Boolean {
        return this == BatteryStatus.Discharging || this == BatteryStatus.NotCharging
    }

    private const val PREFERENCES_NAME = "battery_session_store"
    private const val KEY_DISCHARGING_STARTED_AT_MILLIS = "discharging_started_at_millis"
    private const val KEY_LAST_STATUS = "last_status"
}