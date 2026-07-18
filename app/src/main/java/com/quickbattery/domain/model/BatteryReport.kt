package com.quickbattery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class BatteryReport(
    val levelPercent: Int?,
    val remainingRuntimeMillis: Long?,
    val estimatedFullRuntimeMillis: Long?,
    val sinceLastChargeMillis: Long?,
    val recordSinceLastChargeMillis: Long?,
    val batteryHealth: String?,
    val chargeCycles: Int?,
    val isCharging: Boolean,
    val usagePermissionGranted: Boolean,
    val insights: List<BatteryInsight>,
    val appUsage: List<AppBatteryUsage>,
    val generatedAtMillis: Long,
)
