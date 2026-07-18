package com.quickbattery.data.provider

import com.quickbattery.domain.model.BatteryStatus
import com.quickbattery.domain.model.BatterySnapshot

interface BatteryDataProvider {
    suspend fun getBatterySnapshot(): BatterySnapshot

    suspend fun getLastDischargingTimestampMillis(): Long?

    suspend fun getRecentBatteryLevelSamples(lookbackWindowMillis: Long): List<BatteryLevelSample>

    suspend fun updateSinceLastChargeRecord(candidateMillis: Long?): Long?

    suspend fun updateFullRuntimeEstimate(candidateMillis: Long?): Long?

    suspend fun getAppUsageStats(
        sinceMillis: Long,
        untilMillis: Long,
    ): List<AppUsageStat>

    fun hasUsageStatsPermission(): Boolean
}

data class AppUsageStat(
    val packageName: String,
    val appName: String,
    val iconPng: ByteArray?,
    val screenOnTimeMillis: Long,
)

data class BatteryLevelSample(
    val timestampMillis: Long,
    val levelPercent: Int,
    val status: BatteryStatus,
)
