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
    val energyNanoWattHours: Long?,
    val chargeCounterMicroAmpHours: Int?,
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
