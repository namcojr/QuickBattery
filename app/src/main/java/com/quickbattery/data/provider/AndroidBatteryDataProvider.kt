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
import kotlin.math.roundToInt
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

        val batteryStatusCode = readBatteryStatusCode(batteryIntent)
        val batteryPluggedCode = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val batteryHealthCode = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1

        val levelPercent = readBatteryLevelPercent(batteryIntent)
        val status = mapBatteryStatus(batteryStatusCode)
        val chargingSource = mapChargingSource(batteryPluggedCode)
        val health = mapBatteryHealth(batteryHealthCode)
        val healthPercent = getStateOfHealthPercent()
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
            healthPercent = healthPercent,
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
            seriesCellCountHint = inferSeriesCellCountFromCapacity(),
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
        val now = System.currentTimeMillis()
        val samples = BatteryLevelHistoryStore.getRecentSamples(
            context = context,
            lookbackWindowMillis = LAST_CHARGE_LOOKBACK_WINDOW_MILLIS,
        )

        // The most recent moment we have hard evidence the device was charging (a Charging/Full
        // sample, or a battery-level increase). Any candidate discharge-start that predates this is
        // from a stale, already-superseded cycle and must be discarded.
        val lastChargeEvidenceMillis = findLastChargeEvidenceMillis(samples)
        val historyTimestamp = inferLastDischargingTimestampFromHistory(samples, lastChargeEvidenceMillis)

        val sessionTimestamp = BatterySessionStore.getLastDischargingStartMillis(context)
            ?.takeIf { isNotStale(it, lastChargeEvidenceMillis) }
        val usageEventsTimestamp = readLastDischargingTimestampFromUsageEvents()
            ?.takeIf { isNotStale(it, lastChargeEvidenceMillis) }

        // Prefer the precise unplug instant from a broadcast, then the OS discharging event, and
        // only fall back to the coarser history inference. All are validated against the newest
        // charging evidence, so a stale previous-session value can never keep the timer running.
        return@withContext (sessionTimestamp ?: usageEventsTimestamp ?: historyTimestamp)
            ?.takeIf { it in 0L..now }
    }

    private fun isNotStale(
        candidateMillis: Long,
        lastChargeEvidenceMillis: Long?,
    ): Boolean {
        return lastChargeEvidenceMillis == null || candidateMillis >= lastChargeEvidenceMillis
    }


    override suspend fun resetCalculationData() = withContext(Dispatchers.Default) {
        BatteryRecordStore.clear(context)
        BatteryLevelHistoryStore.clear(context)
        BatterySessionStore.clear(context)
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

        val usageByPackage = usageDurationsByPackage(
            manager = manager,
            sinceMillis = sinceMillis,
            untilMillis = untilMillis,
        )
        if (usageByPackage.isEmpty()) {
            return@withContext emptyList()
        }

        usageByPackage
            .mapNotNull { (packageName, totalActiveTimeMillis) ->
                if (totalActiveTimeMillis <= 0L) {
                    return@mapNotNull null
                }
                runCatching {
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    val appLabel = packageManager.getApplicationLabel(appInfo).toString()
                    val iconDrawable = packageManager.getApplicationIcon(appInfo)

                    AppUsageStat(
                        packageName = packageName,
                        appName = appLabel,
                        iconPng = iconDrawable.toPngByteArray(),
                        screenOnTimeMillis = totalActiveTimeMillis,
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.screenOnTimeMillis }
    }

    // Per-package active time within the window. Combines two signals so that apps active without
    // holding the phone's own screen -- most importantly navigation running via Android Auto, which
    // projects to the car display but runs a foreground service on the phone -- are still counted:
    //   1. Foreground activity + foreground-service intervals derived from UsageEvents (captures
    //      Android Auto / background-projected usage the visible-time aggregate misses).
    //   2. The classic queryUsageStats visible/foreground total as a fallback baseline.
    // The larger of the two is kept per package.
    private fun usageDurationsByPackage(
        manager: UsageStatsManager,
        sinceMillis: Long,
        untilMillis: Long,
    ): Map<String, Long> {
        val eventDurations = usageDurationsFromEvents(manager, sinceMillis, untilMillis)
        val visibleDurations = usageDurationsFromStats(manager, sinceMillis, untilMillis)

        val merged = HashMap<String, Long>(eventDurations)
        visibleDurations.forEach { (packageName, millis) ->
            val existing = merged[packageName] ?: 0L
            merged[packageName] = maxOf(existing, millis)
        }
        return merged
    }

    private fun usageDurationsFromStats(
        manager: UsageStatsManager,
        sinceMillis: Long,
        untilMillis: Long,
    ): Map<String, Long> {
        val usageStats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, sinceMillis, untilMillis)
        if (usageStats.isNullOrEmpty()) {
            return emptyMap()
        }

        val usageByPackage = HashMap<String, Long>()
        usageStats.forEach { usage ->
            val usageDurationMillis = usage.visibleTimeMillis()
            if (usageDurationMillis <= 0L) return@forEach

            val existing = usageByPackage[usage.packageName] ?: 0L
            usageByPackage[usage.packageName] = existing + usageDurationMillis
        }
        return usageByPackage
    }

    private fun usageDurationsFromEvents(
        manager: UsageStatsManager,
        sinceMillis: Long,
        untilMillis: Long,
    ): Map<String, Long> {
        val events = runCatching { manager.queryEvents(sinceMillis, untilMillis) }.getOrNull()
            ?: return emptyMap()
        val event = UsageEvents.Event()

        val intervalsByPackage = HashMap<String, MutableList<LongArray>>()
        val foregroundStart = HashMap<String, Long>()
        val serviceStart = HashMap<String, Long>()

        fun addInterval(packageName: String, start: Long, end: Long) {
            val clampedStart = start.coerceAtLeast(sinceMillis)
            val clampedEnd = end.coerceAtMost(untilMillis)
            if (clampedEnd > clampedStart) {
                intervalsByPackage.getOrPut(packageName) { mutableListOf() }
                    .add(longArrayOf(clampedStart, clampedEnd))
            }
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND ->
                    foregroundStart[packageName] = event.timeStamp

                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val start = foregroundStart.remove(packageName) ?: sinceMillis
                    addInterval(packageName, start, event.timeStamp)
                }

                FOREGROUND_SERVICE_START_EVENT ->
                    serviceStart[packageName] = event.timeStamp

                FOREGROUND_SERVICE_STOP_EVENT -> {
                    val start = serviceStart.remove(packageName) ?: sinceMillis
                    addInterval(packageName, start, event.timeStamp)
                }
            }
        }

        // Close intervals still open at the end of the window (app or service never went
        // background before "until", e.g. navigation still running when the report is generated).
        foregroundStart.forEach { (packageName, start) -> addInterval(packageName, start, untilMillis) }
        serviceStart.forEach { (packageName, start) -> addInterval(packageName, start, untilMillis) }

        return intervalsByPackage.mapValues { (_, intervals) -> mergedIntervalDurationMillis(intervals) }
    }

    // Total covered duration of a set of intervals, merging overlaps so concurrent foreground +
    // foreground-service activity for the same app is not double-counted.
    private fun mergedIntervalDurationMillis(intervals: List<LongArray>): Long {
        if (intervals.isEmpty()) {
            return 0L
        }

        val sorted = intervals.sortedBy { it[0] }
        var total = 0L
        var currentStart = sorted.first()[0]
        var currentEnd = sorted.first()[1]

        for (index in 1 until sorted.size) {
            val start = sorted[index][0]
            val end = sorted[index][1]
            if (start <= currentEnd) {
                if (end > currentEnd) {
                    currentEnd = end
                }
            } else {
                total += currentEnd - currentStart
                currentStart = start
                currentEnd = end
            }
        }
        total += currentEnd - currentStart
        return total
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

    private fun readBatteryStatusCode(intent: Intent?): Int {
        val statusFromIntent = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        if (statusFromIntent != -1) {
            return statusFromIntent
        }

        return getIntBatteryProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: -1
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
        // Prefer the fuel-gauge's live voltage node: EXTRA_VOLTAGE is often quantized to coarse
        // steps (e.g. 4000/3900 mV) on many OEMs, whereas /sys/.../voltage_now exposes the real
        // instantaneous reading (e.g. 4133 mV). Fall back to the broadcast extra when the node is
        // unreadable (SELinux-restricted on some devices).
        readPreciseVoltageMilliVolts()?.let { return it }

        val rawVoltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE) ?: return null
        return normalizeVoltageToMilliVolts(rawVoltage)
    }

    private fun readPreciseVoltageMilliVolts(): Int? {
        VOLTAGE_NOW_SYSFS_PATHS.forEach { path ->
            val rawMicroVolts = runCatching {
                val file = java.io.File(path)
                if (!file.canRead()) return@runCatching null
                file.readText().trim().toIntOrNull()
            }.getOrNull() ?: return@forEach

            // voltage_now is documented in microvolts; some panels report millivolts directly.
            val normalized = normalizeVoltageToMilliVolts(rawMicroVolts)
            if (normalized != null && normalized in PLAUSIBLE_VOLTAGE_MILLI_VOLTS_RANGE) {
                return normalized
            }
        }
        return null
    }

    // Series cell count inferred from the ratio between the framework "battery" pack capacity and a
    // vendor fuel-gauge's per-cell capacity. Dual-cell SuperVOOC/Warp packs (OPPO/OnePlus/realme)
    // market the doubled figure at the framework node (e.g. 7500 mAh) while the gauge exposes the
    // real single-cell capacity (~3760 mAh); the ~2:1 ratio reveals the 2S wiring even when
    // BATTERY_PROPERTY_ENERGY_COUNTER is unavailable. Best-effort; returns null when unreadable.
    private fun inferSeriesCellCountFromCapacity(): Int? {
        val packCapacityMicroAmpHours = readFirstReadableCapacityMicroAmpHours(PACK_CAPACITY_SYSFS_PATHS)
            ?: return null
        val cellCapacityMicroAmpHours = readFirstReadableCapacityMicroAmpHours(CELL_CAPACITY_SYSFS_PATHS)
            ?: return null

        return (packCapacityMicroAmpHours.toDouble() / cellCapacityMicroAmpHours.toDouble())
            .roundToInt()
            .takeIf { it >= 1 }
            ?.coerceAtMost(MAX_SERIES_CELL_COUNT)
    }

    private fun readFirstReadableCapacityMicroAmpHours(paths: List<String>): Long? {
        paths.forEach { path ->
            val value = runCatching {
                val file = java.io.File(path)
                if (!file.canRead()) return@runCatching null
                file.readText().trim().toLongOrNull()
            }.getOrNull()
            if (value != null && value >= MIN_PLAUSIBLE_CELL_CAPACITY_MICRO_AMP_HOURS) {
                return value
            }
        }
        return null
    }

    private fun getStateOfHealthPercent(): Int? {
        // Primary (Android 14+): BatteryManager.BATTERY_PROPERTY_STATE_OF_HEALTH returns the real
        // state of health as a percentage. The constant isn't in older compile SDKs, so resolve its
        // id via reflection against the device framework (same approach as the cycle-count property).
        stateOfHealthBatteryPropertyId?.let { property ->
            getIntBatteryProperty(property)?.takeIf { it in 1..100 }?.let { return it }
        }

        // Best-effort fallback: some OEM fuel gauges expose a numeric SOH via sysfs.
        STATE_OF_HEALTH_SYSFS_PATHS.forEach { path ->
            val soh = runCatching {
                val file = java.io.File(path)
                if (!file.canRead()) return@runCatching null
                file.readText().trim().toIntOrNull()
            }.getOrNull()
            if (soh != null && soh in 1..100) {
                return soh
            }
        }
        return null
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

    private fun inferLastDischargingTimestampFromHistory(
        samples: List<BatteryLevelSample>,
        lastChargeEvidenceMillis: Long?,
    ): Long? {
        if (samples.size < 2 || lastChargeEvidenceMillis == null) {
            return null
        }

        // Charging stopped somewhere between the last charging evidence and the first discharging
        // sample observed after it. When those samples are close together the discharging sample is
        // a reliable unplug marker; when the gap is large (sparse sampling on OEM-restricted
        // devices, or the app was closed across the whole cycle) the last confirmed charging
        // timestamp is the best available lower bound -- far better than falling through to a stale
        // previous-session value.
        val firstDischargingAfterCharge = samples
            .asSequence()
            .filter {
                it.status.isDischargingState() && it.timestampMillis > lastChargeEvidenceMillis
            }
            .minByOrNull { it.timestampMillis }
            ?: return lastChargeEvidenceMillis

        val transitionGapMillis = firstDischargingAfterCharge.timestampMillis - lastChargeEvidenceMillis
        return if (transitionGapMillis in 0L..MAX_HISTORY_TRANSITION_GAP_MILLIS) {
            firstDischargingAfterCharge.timestampMillis
        } else {
            lastChargeEvidenceMillis
        }
    }

    // Newest timestamp with hard evidence the device was charging: a Charging/Full sample, or a
    // battery-level increase between consecutive samples (some OEMs mislabel the status but the
    // rising level is unambiguous proof a charge occurred).
    private fun findLastChargeEvidenceMillis(samples: List<BatteryLevelSample>): Long? {
        var result: Long? = null
        var previous: BatteryLevelSample? = null
        samples.forEach { sample ->
            val prev = previous
            val levelRose = prev != null && sample.levelPercent > prev.levelPercent
            if (sample.status.isChargingState() || levelRose) {
                result = sample.timestampMillis
            }
            previous = sample
        }
        return result
    }

    private fun BatteryStatus.isChargingState(): Boolean {
        return this == BatteryStatus.Charging || this == BatteryStatus.Full
    }

    private fun BatteryStatus.isDischargingState(): Boolean {
        return this == BatteryStatus.Discharging || this == BatteryStatus.NotCharging
    }

    private companion object {
        private const val LAST_CHARGE_LOOKBACK_WINDOW_MILLIS = 14L * 24L * 60L * 60L * 1000L
        private const val MAX_HISTORY_TRANSITION_GAP_MILLIS = 20L * 60L * 1000L
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

        // UsageEvents.Event.FOREGROUND_SERVICE_START / _STOP (API 29+). Declared as literals so the
        // foreground-service intervals are matched on capable devices while still compiling and
        // running down to minSdk 28 (where these events simply never occur).
        private const val FOREGROUND_SERVICE_START_EVENT = 19
        private const val FOREGROUND_SERVICE_STOP_EVENT = 20

        // Candidate fuel-gauge nodes exposing the precise instantaneous voltage. Read best-effort;
        // unreadable on SELinux-restricted devices, in which case we fall back to EXTRA_VOLTAGE.
        private val VOLTAGE_NOW_SYSFS_PATHS = listOf(
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/bms/voltage_now",
        )

        // Best-effort numeric state-of-health nodes used only when the framework property is absent.
        private val STATE_OF_HEALTH_SYSFS_PATHS = listOf(
            "/sys/class/power_supply/battery/state_of_health",
            "/sys/class/power_supply/bms/state_of_health",
        )

        // Upper bound for inferred series cells; phone packs are 1S or 2S, 4 leaves headroom.
        private const val MAX_SERIES_CELL_COUNT = 4
        // Reject non-capacity payloads (e.g. 0 or percentage nodes) below ~1000 mAh in microamp-hours.
        private const val MIN_PLAUSIBLE_CELL_CAPACITY_MICRO_AMP_HOURS = 1_000_000L

        // Framework "battery" node reports the marketed pack capacity, which is doubled on dual-cell
        // SuperVOOC/Warp packs; prefer the design figure, falling back to the learned full capacity.
        private val PACK_CAPACITY_SYSFS_PATHS = listOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/battery/charge_full",
        )

        // Vendor fuel-gauge nodes exposing the real per-cell capacity used to derive the 2S ratio.
        private val CELL_CAPACITY_SYSFS_PATHS = listOf(
            "/sys/class/power_supply/mtk-battery/charge_full_design",
            "/sys/class/power_supply/mtk-battery/charge_full",
            "/sys/class/power_supply/bms/charge_full_design",
            "/sys/class/power_supply/bms/charge_full",
        )

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

        private val stateOfHealthBatteryPropertyId: Int? by lazy {
            runCatching {
                BatteryManager::class.java.getField("BATTERY_PROPERTY_STATE_OF_HEALTH").getInt(null)
            }.getOrNull()
        }
    }
}
