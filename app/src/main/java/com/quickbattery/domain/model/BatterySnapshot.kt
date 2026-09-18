package com.quickbattery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class BatterySnapshot(
    val levelPercent: Int?,
    val status: BatteryStatus,
    val chargingSource: ChargingSource?,
    val health: BatteryHealth?,
    val healthPercent: Int?,
    val voltageMillivolts: Int?,
    val temperatureCelsius: Float?,
    val technology: String?,
    val currentMicroAmps: Int?,
    val averageCurrentMicroAmps: Int?,
    // Battery-side charging power resolved by the charging meter, when it has enough evidence.
    val chargingPowerMilliWatts: Int?,
    // True when that power is a charge-counter average (no live calibration learned yet).
    val chargingPowerFromCounter: Boolean,
    val energyNanoWattHours: Long?,
    val chargeCounterMicroAmpHours: Int?,
    // Best-effort series cell count: from a pack-level voltage reading, the learned current
    // calibration, or sysfs capacity nodes (e.g. dual-cell OPPO/OnePlus SuperVOOC packs).
    val seriesCellCountHint: Int?,
    val chargeCycles: Int?,
    val batterySaverEnabled: Boolean,
    val timestampMillis: Long,
)

enum class BatteryStatus {
    Unknown,
    Charging,
    Discharging,
    Full,
    NotCharging,
}

enum class ChargingSource {
    Ac,
    Usb,
    Wireless,
    Dock,
    Unknown,
}

enum class BatteryHealth {
    Unknown,
    Good,
    Overheat,
    Dead,
    OverVoltage,
    UnspecifiedFailure,
    Cold,
}
