package com.quickbattery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class AppBatteryUsage(
    val packageName: String,
    val appName: String,
    val iconPng: ByteArray?,
    val screenOnTimeMillis: Long,
    val estimatedBatteryContributionPercent: Float?,
)
