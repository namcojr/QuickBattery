package com.quickbattery.data.provider

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.quickbattery.domain.model.BatteryHealth
import com.quickbattery.domain.model.BatterySnapshot
import com.quickbattery.domain.model.BatteryStatus
import com.quickbattery.domain.model.ChargingSource

@Singleton
class AndroidBatteryDataProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : BatteryDataProvider {

    private val batteryManager: BatteryManager? by lazy {
        context.getSystemService(BatteryManager::class.java)
    }
    private val usageStatsManager: UsageStatsManager? by lazy {
        context.getSystemService(UsageStatsManager::class.java)
    }
    private val powerManager: PowerManager? by lazy {
        context.getSystemService(PowerManager::class.java)
    }
    private val appOpsManager: AppOpsManager? by lazy {
        context.getSystemService(AppOpsManager::class.java)
    }

    override suspend fun getBatterySnapshot(): BatterySnapshot = withContext(Dispatchers.Default) {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val batteryStatusCode = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val batteryPluggedCode = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val batteryHealthCode = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1

        val levelPercent = readBatteryLevelPercent(batteryIntent)
        val status = mapBatteryStatus(batteryStatusCode)
        val chargingSource = mapChargingSource(batteryPluggedCode)
        val health = mapBatteryHealth(batteryHealthCode)
        val voltageMillivolts = getVoltageExtraInMilliVolts(batteryIntent)
        val temperatureCelsius = batteryIntent
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            ?.takeIf { it > 0 }
            ?.div(10f)
        val technology = batteryIntent
            ?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
            ?.takeIf { it.isNotBlank() }

        val snapshot = BatterySnapshot(
            levelPercent = levelPercent,
            status = status,
            chargingSource = chargingSource,
            health = health,
            voltageMillivolts = voltageMillivolts,
            temperatureCelsius = temperatureCelsius,
            technology = technology,
            currentMicroAmps = getCurrentBatteryPropertyInMicroAmps(
                property = BatteryManager.BATTERY_PROPERTY_CURRENT_NOW,
                status = status,
                levelPercent = levelPercent,
                voltageMillivolts = voltageMillivolts,
            ),
            averageCurrentMicroAmps = getCurrentBatteryPropertyInMicroAmps(
                property = BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE,
                status = status,
                levelPercent = levelPercent,
                voltageMillivolts = voltageMillivolts,
            ),
            energyNanoWattHours = getLongBatteryProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
            chargeCounterMicroAmpHours = getIntBatteryProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            chargeCycles = getCycleCount(batteryIntent),
            batterySaverEnabled = powerManager?.isPowerSaveMode == true,
            timestampMillis = System.currentTimeMillis(),
        )

        BatterySessionStore.updateFromSnapshot(
            context = context,
            status = snapshot.status,
            timestampMillis = snapshot.timestampMillis,
        )
        BatteryLevelHistoryStore.appendSnapshotSample(
            context = context,
            snapshot = snapshot,
        )

        snapshot
    }

    override suspend fun getLastDischargingTimestampMillis(): Long? = withContext(Dispatchers.Default) {
        val usageEventsTimestamp = readLastDischargingTimestampFromUsageEvents()
        val localSessionTimestamp = BatterySessionStore.getLastDischargingStartMillis(context)
        val historyTimestamp = inferLastDischargingTimestampFromHistory()

        return@withContext listOfNotNull(usageEventsTimestamp, localSessionTimestamp, historyTimestamp).maxOrNull()
    }

    override suspend fun getRecentBatteryLevelSamples(
        lookbackWindowMillis: Long,
    ): List<BatteryLevelSample> = withContext(Dispatchers.Default) {
        BatteryLevelHistoryStore.getRecentSamples(
            context = context,
            lookbackWindowMillis = lookbackWindowMillis,
        )
    }

    override suspend fun updateSinceLastChargeRecord(
        candidateMillis: Long?,
    ): Long? = withContext(Dispatchers.Default) {
        BatteryRecordStore.updateAndGetLongestSinceLastCharge(
            context = context,
            candidateMillis = candidateMillis,
        )
    }

    override suspend fun updateFullRuntimeEstimate(
        candidateMillis: Long?,
    ): Long? = withContext(Dispatchers.Default) {
        BatteryRecordStore.updateAndGetFullRuntimeEstimate(
            context = context,
            candidateMillis = candidateMillis,
        )
    }

    override suspend fun getAppUsageStats(
        sinceMillis: Long,
        untilMillis: Long,
    ): List<AppUsageStat> = withContext(Dispatchers.IO) {
        if (!hasUsageStatsPermission()) {
            return@withContext emptyList()
        }

        val manager = usageStatsManager ?: return@withContext emptyList()
        val packageManager = context.packageManager
        val usageStats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, sinceMillis, untilMillis)
        if (usageStats.isNullOrEmpty()) {
            return@withContext emptyList()
        }

        val usageByPackage = mutableMapOf<String, Long>()
        usageStats.forEach { usage ->
            val usageDurationMillis = usage.visibleTimeMillis()
            if (usageDurationMillis <= 0L) return@forEach

            val existing = usageByPackage[usage.packageName] ?: 0L
            usageByPackage[usage.packageName] = existing + usageDurationMillis
        }

        usageByPackage
            .mapNotNull { (packageName, totalVisibleTime) ->
                runCatching {
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    val appLabel = packageManager.getApplicationLabel(appInfo).toString()
                    val iconDrawable = packageManager.getApplicationIcon(appInfo)

                    AppUsageStat(
                        packageName = packageName,
                        appName = appLabel,
                        iconPng = iconDrawable.toPngByteArray(),
                        screenOnTimeMillis = totalVisibleTime,
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.screenOnTimeMillis }
    }

    override fun hasUsageStatsPermission(): Boolean {
        val appOps = appOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }

        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun readBatteryLevelPercent(intent: Intent?): Int? {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) {
            return (level * 100f / scale.toFloat()).toInt().coerceIn(0, 100)
        }

        val property = getIntBatteryProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return property?.coerceIn(0, 100)
    }

    private fun getIntBatteryProperty(property: Int): Int? {
        val manager = batteryManager ?: return null
        val value = runCatching { manager.getIntProperty(property) }.getOrNull() ?: return null

        return value.takeUnless { value == Int.MIN_VALUE }
    }

    private fun getLongBatteryProperty(property: Int): Long? {
        val manager = batteryManager ?: return null
        val value = runCatching { manager.getLongProperty(property) }.getOrNull() ?: return null
        return value.takeUnless { it == Long.MIN_VALUE }
    }

    private fun getVoltageExtraInMilliVolts(intent: Intent?): Int? {
        val rawVoltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE) ?: return null
        return normalizeVoltageToMilliVolts(rawVoltage)
    }

    private fun normalizeVoltageToMilliVolts(rawVoltage: Int): Int? {
        if (rawVoltage <= 0) {
            return null
        }

        // Android documents EXTRA_VOLTAGE as millivolts, but some OEM builds expose V / dV / cV
        // or even microvolts. Normalize common variants so UI and power heuristics stay correct.
        return when {
            rawVoltage in PLAUSIBLE_VOLTAGE_MILLI_VOLTS_RANGE -> rawVoltage
            rawVoltage in PLAUSIBLE_VOLTAGE_VOLTS_RANGE -> rawVoltage * MILLI_VOLTS_PER_VOLT
            rawVoltage in PLAUSIBLE_VOLTAGE_DECI_VOLTS_RANGE -> rawVoltage * MILLI_VOLTS_PER_DECI_VOLT
            rawVoltage in PLAUSIBLE_VOLTAGE_CENTI_VOLTS_RANGE -> rawVoltage * MILLI_VOLTS_PER_CENTI_VOLT
            rawVoltage in PLAUSIBLE_VOLTAGE_MICRO_VOLTS_RANGE -> rawVoltage / MICRO_VOLTS_PER_MILLI_VOLT
            else -> rawVoltage
        }
    }

    private fun getCurrentBatteryPropertyInMicroAmps(
        property: Int,
        status: BatteryStatus,
        levelPercent: Int?,
        voltageMillivolts: Int?,
    ): Int? {
        val rawCurrent = getIntBatteryProperty(property) ?: return null
        return normalizeCurrentToMicroAmps(
            rawCurrent = rawCurrent,
            status = status,
            levelPercent = levelPercent,
            voltageMillivolts = voltageMillivolts,
        )
    }

    private fun normalizeCurrentToMicroAmps(
        rawCurrent: Int,
        status: BatteryStatus,
        levelPercent: Int?,
        voltageMillivolts: Int?,
    ): Int {
        if (!status.isChargingState()) {
            return rawCurrent
        }

        val absoluteRawCurrent = abs(rawCurrent)
        if (absoluteRawCurrent == 0) {
            return rawCurrent
        }

        // Android reports battery current in microamps, but some OEM ROMs expose milliamp values.
        // Detect obvious mA payloads while charging and normalize to microamps.
        val likelyMilliAmpUnits =
            absoluteRawCurrent <= MAX_REASONABLE_CHARGING_CURRENT_MILLI_AMPS &&
                (levelPercent == null || levelPercent < TRICKLE_CHARGE_LEVEL_PERCENT_THRESHOLD) &&
                isImplausiblyLowChargingPower(
                    currentAssumingMicroAmps = absoluteRawCurrent,
                    voltageMillivolts = voltageMillivolts,
                )

        if (!likelyMilliAmpUnits) {
            return rawCurrent
        }

        val normalizedMagnitude = absoluteRawCurrent * MICRO_AMPS_PER_MILLI_AMP
        return if (rawCurrent < 0) -normalizedMagnitude else normalizedMagnitude
    }

    private fun isImplausiblyLowChargingPower(
        currentAssumingMicroAmps: Int,
        voltageMillivolts: Int?,
    ): Boolean {
        val voltage = voltageMillivolts ?: return currentAssumingMicroAmps < MIN_PLAUSIBLE_CHARGING_CURRENT_MICRO_AMPS
        val chargingPowerMilliWatts =
            (currentAssumingMicroAmps.toDouble() * voltage.toDouble()) / MICRO_AMPS_MILLIVOLTS_PER_MILLI_WATT
        return chargingPowerMilliWatts < MIN_PLAUSIBLE_CHARGING_POWER_MILLI_WATTS
    }

    private fun getCycleCount(batteryIntent: Intent?): Int? {
        // Preferred source (Android 14+): the sticky ACTION_BATTERY_CHANGED broadcast exposes the
        // charge cycle count as an intent extra, which OEMs populate more reliably than the
        // BatteryManager property on many devices.
        val intentCycleCount = batteryIntent
            ?.getIntExtra(EXTRA_CYCLE_COUNT, Int.MIN_VALUE)
            ?.takeIf { it >= 0 }
        if (intentCycleCount != null) {
            return intentCycleCount
        }

        // Fallback: BatteryManager.BATTERY_PROPERTY_CYCLE_COUNT, resolved via reflection so the app
        // keeps compiling against older SDKs while still reading the value on capable devices.
        val cycleCountProperty = cycleCountBatteryPropertyId ?: return null
        val manager = batteryManager ?: return null
        val value = runCatching {
            manager.getIntProperty(cycleCountProperty)
        }.getOrNull() ?: return null

        return value.takeIf { it >= 0 }
    }

    private fun UsageStats.visibleTimeMillis(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            totalTimeVisible
        } else {
            @Suppress("DEPRECATION")
            totalTimeInForeground
        }
    }

    private fun Drawable.toPngByteArray(): ByteArray? {
        val bitmap = toBitmapOrNull() ?: return null
        return ByteArrayOutputStream().use { outputStream ->
            val compressed = bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            if (compressed) outputStream.toByteArray() else null
        }
    }

    private fun Drawable.toBitmapOrNull(): Bitmap? {
        if (this is BitmapDrawable) {
            return bitmap
        }

        val safeWidth = intrinsicWidth.coerceAtLeast(1)
        val safeHeight = intrinsicHeight.coerceAtLeast(1)
        return runCatching {
            Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                setBounds(0, 0, canvas.width, canvas.height)
                draw(canvas)
            }
        }.getOrNull()
    }

    private fun mapBatteryStatus(status: Int): BatteryStatus {
        return when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> BatteryStatus.Charging
            BatteryManager.BATTERY_STATUS_DISCHARGING -> BatteryStatus.Discharging
            BatteryManager.BATTERY_STATUS_FULL -> BatteryStatus.Full
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> BatteryStatus.NotCharging
            else -> BatteryStatus.Unknown
        }
    }

    private fun mapChargingSource(plugged: Int): ChargingSource {
        return when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> ChargingSource.Ac
            BatteryManager.BATTERY_PLUGGED_USB -> ChargingSource.Usb
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> ChargingSource.Wireless
            BatteryManager.BATTERY_PLUGGED_DOCK -> ChargingSource.Dock
            else -> ChargingSource.Unknown
        }
    }

    private fun mapBatteryHealth(health: Int): BatteryHealth {
        return when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealth.Good
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryHealth.Overheat
            BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealth.Dead
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryHealth.OverVoltage
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> BatteryHealth.UnspecifiedFailure
            BatteryManager.BATTERY_HEALTH_COLD -> BatteryHealth.Cold
            else -> BatteryHealth.Unknown
        }
    }

    private fun readLastDischargingTimestampFromUsageEvents(): Long? {
        if (!hasUsageStatsPermission()) {
            return null
        }
        val dischargingEventType = dischargingUsageEventType ?: return null
        val manager = usageStatsManager ?: return null

        val now = System.currentTimeMillis()
        val from = now - LAST_CHARGE_LOOKBACK_WINDOW_MILLIS
        val events = manager.queryEvents(from, now)
        val event = UsageEvents.Event()

        var lastDischarging: Long? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == dischargingEventType) {
                lastDischarging = event.timeStamp
            }
        }

        return lastDischarging
    }

    private fun inferLastDischargingTimestampFromHistory(): Long? {
        val samples = BatteryLevelHistoryStore.getRecentSamples(
            context = context,
            lookbackWindowMillis = LAST_CHARGE_LOOKBACK_WINDOW_MILLIS,
        )
        if (samples.size < 2) {
            return null
        }

        var previousStatus: BatteryStatus? = null
        var lastTransitionTimestamp: Long? = null

        samples.forEach { sample ->
            val nowStatus = sample.status
            if (previousStatus?.isChargingState() == true && nowStatus.isDischargingState()) {
                lastTransitionTimestamp = sample.timestampMillis
            }
            previousStatus = nowStatus
        }

        return lastTransitionTimestamp
    }

    private fun BatteryStatus.isChargingState(): Boolean {
        return this == BatteryStatus.Charging || this == BatteryStatus.Full
    }

    private fun BatteryStatus.isDischargingState(): Boolean {
        return this == BatteryStatus.Discharging || this == BatteryStatus.NotCharging
    }

    private companion object {
        private const val LAST_CHARGE_LOOKBACK_WINDOW_MILLIS = 14L * 24L * 60L * 60L * 1000L
        private const val MILLI_VOLTS_PER_VOLT = 1_000
        private const val MILLI_VOLTS_PER_DECI_VOLT = 100
        private const val MILLI_VOLTS_PER_CENTI_VOLT = 10
        private const val MICRO_VOLTS_PER_MILLI_VOLT = 1_000
        private const val MICRO_AMPS_PER_MILLI_AMP = 1_000
        private const val MICRO_AMPS_MILLIVOLTS_PER_MILLI_WATT = 1_000_000.0
        private const val MAX_REASONABLE_CHARGING_CURRENT_MILLI_AMPS = 20_000
        private const val TRICKLE_CHARGE_LEVEL_PERCENT_THRESHOLD = 97
        private const val MIN_PLAUSIBLE_CHARGING_CURRENT_MICRO_AMPS = 50_000
        private const val MIN_PLAUSIBLE_CHARGING_POWER_MILLI_WATTS = 250.0
        private val PLAUSIBLE_VOLTAGE_VOLTS_RANGE = 2..20
        private val PLAUSIBLE_VOLTAGE_DECI_VOLTS_RANGE = 20..200
        private val PLAUSIBLE_VOLTAGE_CENTI_VOLTS_RANGE = 200..2_000
        private val PLAUSIBLE_VOLTAGE_MILLI_VOLTS_RANGE = 2_000..20_000
        private val PLAUSIBLE_VOLTAGE_MICRO_VOLTS_RANGE = 2_000_000..20_000_000

        // BatteryManager.EXTRA_CYCLE_COUNT (public since Android 14). Declared as a literal so the
        // extra is still read on capable devices when compiling against older SDKs.
        private const val EXTRA_CYCLE_COUNT = "android.os.extra.CYCLE_COUNT"

        private val dischargingUsageEventType: Int? by lazy {
            runCatching {
                UsageEvents.Event::class.java.getField("DISCHARGING").getInt(null)
            }.getOrNull()
        }

        private val cycleCountBatteryPropertyId: Int? by lazy {
            runCatching {
                BatteryManager::class.java.getField("BATTERY_PROPERTY_CYCLE_COUNT").getInt(null)
            }.getOrNull()
        }
    }
}
