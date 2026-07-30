package com.quickbattery.data.repository

import com.quickbattery.data.provider.AppUsageStat
import com.quickbattery.data.provider.BatteryDataProvider
import com.quickbattery.data.provider.BatteryLevelSample
import com.quickbattery.di.IoDispatcher
import com.quickbattery.domain.model.AppBatteryUsage
import com.quickbattery.domain.model.BatteryHealth
import com.quickbattery.domain.model.BatteryInsight
import com.quickbattery.domain.model.BatteryReport
import com.quickbattery.domain.model.BatterySnapshot
import com.quickbattery.domain.model.BatteryStatus
import com.quickbattery.domain.model.ChargingSource
import com.quickbattery.domain.repository.BatteryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
class BatteryRepositoryImpl @Inject constructor(
    private val dataProvider: BatteryDataProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : BatteryRepository {

    override suspend fun getBatteryReport(): BatteryReport = withContext(ioDispatcher) {
        val snapshot = dataProvider.getBatterySnapshot()
        val now = System.currentTimeMillis()
        val usagePermissionGranted = dataProvider.hasUsageStatsPermission()
        // Recent window drives the *current-usage* trend regression (we want the last few hours).
        val recentLevelSamples = dataProvider.getRecentBatteryLevelSamples(RUNTIME_TREND_LOOKBACK_WINDOW_MILLIS)
        // Wide window drives the *session-average* baseline: the unplug moment can be days back, so
        // resolving the discharge-start level from the 24h trend window silently loses it on long
        // sessions (the retained history already keeps up to a week of samples).
        val sessionLevelSamples = dataProvider.getRecentBatteryLevelSamples(SESSION_BASELINE_LOOKBACK_WINDOW_MILLIS)

        val lastDischargingMillis = dataProvider.getLastDischargingTimestampMillis()
        val inferredDischargingStartMillis = inferDischargingStartFromSamples(
            snapshot = snapshot,
            samples = recentLevelSamples,
        )
        val effectiveLastDischargingMillis = lastDischargingMillis ?: inferredDischargingStartMillis

        val sinceLastChargeMillis = effectiveLastDischargingMillis
            ?.let { now - it }
            ?.takeIf { it > 0L }

        val recordSinceLastChargeMillis = dataProvider.updateSinceLastChargeRecord(sinceLastChargeMillis)

        val dischargeStartLevelPercent = resolveDischargeStartLevelPercent(
            samples = sessionLevelSamples,
            dischargeStartMillis = effectiveLastDischargingMillis,
        )
        val consumedPercent = calculateConsumedPercent(snapshot, sinceLastChargeMillis, dischargeStartLevelPercent)

        val estimatedFullRuntimeFromHistoryMillis =
            estimateFullRuntimeMillis(sinceLastChargeMillis, consumedPercent)
        val remainingRuntimeFromHistoryMillis =
            estimateRemainingRuntimeMillis(snapshot, estimatedFullRuntimeFromHistoryMillis)

        // Current-usage estimates, computed unconditionally so the "A full charge should last"
        // figure always reflects how the phone is being used right now.
        val remainingRuntimeFromCurrentMillis = estimateRemainingRuntimeFromCurrent(snapshot)
        val runtimeFromTrend = estimateRuntimeFromLevelTrend(
            snapshot = snapshot,
            samples = recentLevelSamples,
        )

        // Always a full 100 -> 0% projection ("if I keep using it like now, a full charge lasts X").
        // Prefer the most current-usage rate available: recent discharge trend, then live current
        // draw, and only fall back to the average since unplugging when neither is available yet.
        val currentUsageFullRuntimeMillis =
            runtimeFromTrend?.fullRuntimeMillis
                ?: estimateFullRuntimeFromRemaining(snapshot, remainingRuntimeFromCurrentMillis)

        // Proven-longevity floor: the whole discharge session is ground truth. If a full charge has
        // already demonstrably lasted longer than the (noisier, short-window) current-usage rate
        // projects, the current-usage figure is under-reporting real endurance -- e.g. a light
        // multi-day session where recent activity is heavier than the session average. Anchor the
        // projection so it never dips below what the session has actually proven achievable.
        val liveFullRuntimeMillis = when {
            currentUsageFullRuntimeMillis != null && estimatedFullRuntimeFromHistoryMillis != null ->
                maxOf(currentUsageFullRuntimeMillis, estimatedFullRuntimeFromHistoryMillis)

            else -> currentUsageFullRuntimeMillis ?: estimatedFullRuntimeFromHistoryMillis
        }

        // Persist the freshest real projection so it can be reused as a warm start. Passing null
        // while charging (no live estimate) leaves the previously saved samples untouched.
        val lastKnownFullRuntimeMillis = dataProvider.updateFullRuntimeEstimate(liveFullRuntimeMillis)

        // While discharging, if the live estimate isn't ready yet (needs ~20m + 1% drop after
        // unplugging) show the last saved projection instead of "Learning...", then keep refining
        // it as fresh trend/current data arrives.
        val estimatedFullRuntimeMillis = when {
            liveFullRuntimeMillis != null -> liveFullRuntimeMillis
            snapshot.status.isDischargingState() -> lastKnownFullRuntimeMillis
            else -> null
        }

        val remainingRuntimeMillis =
            (remainingRuntimeFromHistoryMillis
                ?: remainingRuntimeFromCurrentMillis
                ?: runtimeFromTrend?.remainingRuntimeMillis
                // Warm start: derive remaining from the last known full projection scaled by the
                // current level so both summary figures populate instead of showing "Learning...".
                // estimateRemainingRuntimeMillis returns null while charging, keeping this
                // discharge-only.
                ?: estimateRemainingRuntimeMillis(snapshot, estimatedFullRuntimeMillis))
                // Physical invariant: draining the current (partial) level can never take longer
                // than draining a full 100 -> 0% charge. These two figures come from independent
                // estimators, so clamp remaining to the full-runtime projection to avoid showing a
                // remaining time that is larger than the full-charge time.
                ?.let { remaining ->
                    estimatedFullRuntimeMillis?.let { minOf(remaining, it) } ?: remaining
                }

        val appUsageRaw = if (usagePermissionGranted) {
            val usageWindowStart = effectiveLastDischargingMillis ?: (now - FALLBACK_USAGE_WINDOW_MILLIS)
            dataProvider.getAppUsageStats(
                sinceMillis = usageWindowStart,
                untilMillis = now,
            )
        } else {
            emptyList()
        }

        val appUsage = appUsageRaw.toDomainAppUsage(consumedPercent)
        val insights = buildInsights(snapshot)

        BatteryReport(
            levelPercent = snapshot.levelPercent,
            remainingRuntimeMillis = remainingRuntimeMillis,
            estimatedFullRuntimeMillis = estimatedFullRuntimeMillis,
            sinceLastChargeMillis = sinceLastChargeMillis,
            recordSinceLastChargeMillis = recordSinceLastChargeMillis,
            batteryHealth = formatBatteryHealth(snapshot),
            chargeCycles = snapshot.chargeCycles,
            isCharging = snapshot.status == BatteryStatus.Charging || snapshot.status == BatteryStatus.Full,
            usagePermissionGranted = usagePermissionGranted,
            insights = insights,
            appUsage = appUsage,
            generatedAtMillis = snapshot.timestampMillis,
        )
    }

    private fun estimateFullRuntimeMillis(
        elapsedMillis: Long?,
        consumedPercent: Int?,
    ): Long? {
        val consumed = consumedPercent?.takeIf { it > 0 } ?: return null
        val elapsed = elapsedMillis?.takeIf { it > 0L } ?: return null

        return ((elapsed.toDouble() / consumed.toDouble()) * 100.0)
            .roundToLong()
            .takeIf { it in MIN_RUNTIME_ESTIMATE_MILLIS..MAX_RUNTIME_ESTIMATE_MILLIS }
    }

    private fun estimateRemainingRuntimeMillis(
        snapshot: BatterySnapshot,
        estimatedFullRuntimeMillis: Long?,
    ): Long? {
        if (snapshot.status == BatteryStatus.Charging || snapshot.status == BatteryStatus.Full) {
            return null
        }
        val levelPercent = snapshot.levelPercent ?: return null
        val fullRuntime = estimatedFullRuntimeMillis ?: return null

        return ((fullRuntime.toDouble() * levelPercent.toDouble()) / 100.0)
            .roundToLong()
            .takeIf { it > 0L }
    }

    private fun estimateRemainingRuntimeFromCurrent(snapshot: BatterySnapshot): Long? {
        if (snapshot.status == BatteryStatus.Charging || snapshot.status == BatteryStatus.Full) {
            return null
        }

        val remainingChargeMicroAmpHours = snapshot.chargeCounterMicroAmpHours?.takeIf { it > 0 } ?: return null
        // Prefer the fuel gauge's averaged current: it is far less noisy than the instantaneous
        // reading, yielding a more stable and precise runtime estimate.
        val currentMicroAmps = snapshot.averageCurrentMicroAmps ?: snapshot.currentMicroAmps ?: return null
        val dischargeCurrentMicroAmps = abs(currentMicroAmps)
            .takeIf { it >= MIN_DISCHARGE_CURRENT_MICRO_AMPS }
            ?: return null

        val estimatedMillis = ((remainingChargeMicroAmpHours.toDouble() / dischargeCurrentMicroAmps.toDouble()) *
            3_600_000.0)
            .roundToLong()

        return estimatedMillis.takeIf {
            it in MIN_RUNTIME_ESTIMATE_MILLIS..MAX_RUNTIME_ESTIMATE_MILLIS
        }
    }

    private fun estimateFullRuntimeFromRemaining(
        snapshot: BatterySnapshot,
        remainingRuntimeMillis: Long?,
    ): Long? {
        val remaining = remainingRuntimeMillis ?: return null
        val level = snapshot.levelPercent ?: return null
        if (level <= 0) {
            return null
        }

        val estimate = ((remaining.toDouble() * 100.0) / level.toDouble())
            .roundToLong()

        return estimate.takeIf {
            it in MIN_RUNTIME_ESTIMATE_MILLIS..MAX_RUNTIME_ESTIMATE_MILLIS
        }
    }

    private fun inferDischargingStartFromSamples(
        snapshot: BatterySnapshot,
        samples: List<BatteryLevelSample>,
    ): Long? {
        if (!snapshot.status.isDischargingState() || samples.size < 2) {
            return null
        }

        var previousStatus: BatteryStatus? = null
        var transitionTimestamp: Long? = null

        samples.forEach { sample ->
            if (previousStatus?.isChargingState() == true && sample.status.isDischargingState()) {
                transitionTimestamp = sample.timestampMillis
            }
            previousStatus = sample.status
        }

        if (transitionTimestamp != null) {
            return transitionTimestamp
        }

        val dischargingSamples = samples.filter { it.status.isDischargingState() }
        if (dischargingSamples.size < MIN_TREND_SAMPLE_COUNT) {
            return null
        }

        val oldest = dischargingSamples.first()
        val newest = dischargingSamples.last()
        if (newest.timestampMillis - oldest.timestampMillis < MIN_TREND_ELAPSED_MILLIS) {
            return null
        }

        return oldest.timestampMillis
    }

    private fun estimateRuntimeFromLevelTrend(
        snapshot: BatterySnapshot,
        samples: List<BatteryLevelSample>,
    ): RuntimeEstimate? {
        if (!snapshot.status.isDischargingState()) {
            return null
        }

        val currentLevel = snapshot.levelPercent ?: return null
        val dischargingSamples = samples.filter { it.status.isDischargingState() }
        if (dischargingSamples.size < MIN_TREND_SAMPLE_COUNT) {
            return null
        }

        val oldest = dischargingSamples.first()
        val newest = dischargingSamples.last()
        val elapsedMillis = newest.timestampMillis - oldest.timestampMillis
        if (elapsedMillis < MIN_TREND_ELAPSED_MILLIS) {
            return null
        }

        val consumedPercent = oldest.levelPercent - newest.levelPercent
        if (consumedPercent < MIN_TREND_DROP_PERCENT) {
            return null
        }

        // Least-squares slope across every discharging sample is more robust to per-sample noise
        // than a two-point (oldest/newest) rate.
        val percentPerMillis = dischargeRatePercentPerMillis(dischargingSamples) ?: return null
        if (percentPerMillis <= 0.0) {
            return null
        }

        val remainingRuntimeMillis = (currentLevel.toDouble() / percentPerMillis).roundToLong()
        val fullRuntimeMillis = (100.0 / percentPerMillis).roundToLong()

        if (remainingRuntimeMillis !in MIN_RUNTIME_ESTIMATE_MILLIS..MAX_RUNTIME_ESTIMATE_MILLIS) {
            return null
        }
        if (fullRuntimeMillis !in MIN_RUNTIME_ESTIMATE_MILLIS..MAX_RUNTIME_ESTIMATE_MILLIS) {
            return null
        }

        return RuntimeEstimate(
            remainingRuntimeMillis = remainingRuntimeMillis,
            fullRuntimeMillis = fullRuntimeMillis,
        )
    }

    private fun calculateConsumedPercent(
        snapshot: BatterySnapshot,
        sinceLastChargeMillis: Long?,
        dischargeStartLevelPercent: Int?,
    ): Int? {
        if (sinceLastChargeMillis == null || sinceLastChargeMillis <= 0L) {
            return null
        }
        if (snapshot.status == BatteryStatus.Charging || snapshot.status == BatteryStatus.Full) {
            return null
        }

        val level = snapshot.levelPercent ?: return null
        // Use the actual level when the phone was unplugged as the baseline instead of assuming a
        // full 100% charge; this removes a large error when charging stops below 100%.
        val baseline = (dischargeStartLevelPercent ?: 100).coerceIn(level, 100)
        val consumed = baseline - level
        return consumed.takeIf { it > 0 }
    }

    private fun resolveDischargeStartLevelPercent(
        samples: List<BatteryLevelSample>,
        dischargeStartMillis: Long?,
    ): Int? {
        if (dischargeStartMillis == null || samples.isEmpty()) {
            return null
        }

        // The level when the current discharge session began is the highest level seen on
        // discharging samples at/after the start (the battery only drains while unplugged).
        return samples
            .filter {
                it.status.isDischargingState() &&
                    it.timestampMillis >= dischargeStartMillis - DISCHARGE_START_LEVEL_TOLERANCE_MILLIS
            }
            .maxOfOrNull { it.levelPercent }
    }

    private fun dischargeRatePercentPerMillis(samples: List<BatteryLevelSample>): Double? {
        if (samples.size < MIN_TREND_SAMPLE_COUNT) {
            return null
        }

        val baseTimestamp = samples.first().timestampMillis
        var count = 0.0
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumXY = 0.0
        samples.forEach { sample ->
            val x = (sample.timestampMillis - baseTimestamp).toDouble()
            val y = sample.levelPercent.toDouble()
            count += 1.0
            sumX += x
            sumY += y
            sumXX += x * x
            sumXY += x * y
        }

        val denominator = count * sumXX - sumX * sumX
        if (denominator == 0.0) {
            return null
        }

        // Slope is percent-per-millis and negative while draining; return the positive drain rate.
        val slope = (count * sumXY - sumX * sumY) / denominator
        return (-slope).takeIf { it > 0.0 }
    }

    private fun buildInsights(
        snapshot: BatterySnapshot,
    ): List<BatteryInsight> {
        val list = mutableListOf<BatteryInsight>()

        list += BatteryInsight("Battery Status", snapshot.status.toDisplayName())
        list += BatteryInsight("Charging State", if (snapshot.status.isChargingState()) "Charging" else "Unplugged")
        list += BatteryInsight("Battery Saver", if (snapshot.batterySaverEnabled) "On" else "Off")

        formatBatteryHealth(snapshot)?.let { list += BatteryInsight("Battery Health", it) }
        snapshot.chargeCycles?.let { list += BatteryInsight("Charge Cycles", it.toString()) }
        snapshot.chargingSource?.let {
            if (it != ChargingSource.Unknown) {
                list += BatteryInsight("Charging Source", it.toDisplayName())
            }
        }
        snapshot.levelPercent?.let { list += BatteryInsight("Capacity", "$it%") }
        snapshot.voltageMillivolts?.let { list += BatteryInsight("Voltage", "$it mV") }
        snapshot.temperatureCelsius?.let { list += BatteryInsight("Temperature", "${"%.1f".format(it)} C") }
        snapshot.technology?.let { list += BatteryInsight("Technology", it) }
        snapshot.currentMicroAmps?.let { current ->
            list += BatteryInsight("Battery Current", "${microAmpsToMilliAmps(current)} mA")
        }
        snapshot.averageCurrentMicroAmps?.let { current ->
            list += BatteryInsight("Average Current", "${microAmpsToMilliAmps(current)} mA")
        }
        snapshot.energyNanoWattHours?.let { energy ->
            val milliWattHours = energy / 1_000_000.0
            list += BatteryInsight("Energy Counter", "${"%.0f".format(milliWattHours)} mWh")
        }
        snapshot.chargeCounterMicroAmpHours?.let { counter ->
            list += BatteryInsight("Current Capacity", "${counter / 1000} mAh")
        }

        inferChargingSpeed(snapshot)?.let { list += BatteryInsight("Charging Speed", it) }

        return list
    }

    private fun inferChargingSpeed(snapshot: BatterySnapshot): String? {
        if (!snapshot.status.isChargingState()) {
            return null
        }
        val currentMilliAmps = resolveChargingCurrentMilliAmps(snapshot) ?: return null

        val tier = when {
            currentMilliAmps >= SUPER_VOOC_ESTIMATE_THRESHOLD_MILLI_AMPS -> "Super VOOC"
            currentMilliAmps >= ULTRA_FAST_THRESHOLD_MILLI_AMPS -> "Ultra Fast"
            currentMilliAmps >= FAST_THRESHOLD_MILLI_AMPS -> "Fast"
            currentMilliAmps >= NORMAL_THRESHOLD_MILLI_AMPS -> "Normal"
            currentMilliAmps > 0f -> "Slow"
            else -> return null
        }

        // Charging power P(W) = V(volts) x I(amps) = (mV / 1000) x (mA / 1000).
        val watts = snapshot.voltageMillivolts
            ?.takeIf { it > 0 }
            ?.let { (it.toFloat() / 1000f) * (currentMilliAmps / 1000f) }

        return if (watts != null) {
            "$tier - ${"%.1f".format(watts)} W"
        } else {
            tier
        }
    }

    private fun resolveChargingCurrentMilliAmps(snapshot: BatterySnapshot): Float? {
        val strongestCurrentMicroAmps = listOfNotNull(
            snapshot.currentMicroAmps,
            snapshot.averageCurrentMicroAmps,
        ).map(::abs)
            .maxOrNull()
            ?: return null

        if (strongestCurrentMicroAmps <= 0) {
            return null
        }

        return strongestCurrentMicroAmps / 1000f
    }

    private fun microAmpsToMilliAmps(microAmps: Int): String {
        return "%.0f".format(microAmps / 1000f)
    }

    private fun List<AppUsageStat>.toDomainAppUsage(consumedPercent: Int?): List<AppBatteryUsage> {
        val totalScreenOn = sumOf { it.screenOnTimeMillis }.toDouble()

        return map { usage ->
            val contribution = if (consumedPercent != null && totalScreenOn > 0.0) {
                ((usage.screenOnTimeMillis / totalScreenOn) * consumedPercent)
                    .toFloat()
                    .coerceIn(0f, 100f)
            } else {
                null
            }

            AppBatteryUsage(
                packageName = usage.packageName,
                appName = usage.appName,
                iconPng = usage.iconPng,
                screenOnTimeMillis = usage.screenOnTimeMillis,
                estimatedBatteryContributionPercent = contribution,
            )
        }.sortedByDescending { it.screenOnTimeMillis }
    }

    private fun BatteryStatus.isChargingState(): Boolean {
        return this == BatteryStatus.Charging || this == BatteryStatus.Full
    }

    private fun BatteryStatus.isDischargingState(): Boolean {
        return this == BatteryStatus.Discharging || this == BatteryStatus.NotCharging
    }

    private fun BatteryStatus.toDisplayName(): String {
        return when (this) {
            BatteryStatus.Unknown -> "Unknown"
            BatteryStatus.Charging -> "Charging"
            BatteryStatus.Discharging -> "Discharging"
            BatteryStatus.Full -> "Full"
            BatteryStatus.NotCharging -> "Not Charging"
        }
    }

    private fun ChargingSource.toDisplayName(): String {
        return when (this) {
            ChargingSource.Ac -> "AC"
            ChargingSource.Usb -> "USB"
            ChargingSource.Wireless -> "Wireless"
            ChargingSource.Dock -> "Dock"
            ChargingSource.Unknown -> "Unknown"
        }
    }

    private fun BatteryHealth.toDisplayName(): String {
        return when (this) {
            BatteryHealth.Unknown -> "Unknown"
            BatteryHealth.Good -> "Good"
            BatteryHealth.Overheat -> "Overheat"
            BatteryHealth.Dead -> "Dead"
            BatteryHealth.OverVoltage -> "Over Voltage"
            BatteryHealth.UnspecifiedFailure -> "Unspecified Failure"
            BatteryHealth.Cold -> "Cold"
        }
    }

    // Prefer the real state-of-health percentage (Android 14+) rendered as "82% - Good"; fall back
    // to the categorical BATTERY_HEALTH_* status when the device doesn't report a percentage.
    private fun formatBatteryHealth(snapshot: BatterySnapshot): String? {
        val percent = snapshot.healthPercent?.takeIf { it in 1..100 }
        if (percent != null) {
            return "$percent% - ${healthTierLabel(percent)}"
        }
        return snapshot.health?.toDisplayName()?.takeUnless { it == "Unknown" }
    }

    private fun healthTierLabel(percent: Int): String {
        return when {
            percent >= HEALTH_EXCELLENT_THRESHOLD -> "Excellent"
            percent >= HEALTH_GOOD_THRESHOLD -> "Good"
            percent >= HEALTH_FAIR_THRESHOLD -> "Fair"
            percent >= HEALTH_POOR_THRESHOLD -> "Poor"
            else -> "Replace Soon"
        }
    }

    private fun formatDuration(durationMillis: Long): String {
        val totalMinutes = durationMillis / 60_000L
        val days = totalMinutes / (24L * 60L)
        val hours = (totalMinutes % (24L * 60L)) / 60L
        val minutes = totalMinutes % 60L

        return buildString {
            if (days > 0L) append("${days}d ")
            if (hours > 0L || days > 0L) append("${hours}h ")
            append("${minutes}m")
        }.trim()
    }

    private companion object {
        private const val FALLBACK_USAGE_WINDOW_MILLIS = 24L * 60L * 60L * 1000L
        private const val RUNTIME_TREND_LOOKBACK_WINDOW_MILLIS = 24L * 60L * 60L * 1000L
        // Covers the full retained battery-level history so the unplug baseline can be resolved for
        // multi-day discharge sessions (BatteryLevelHistoryStore retains ~7 days).
        private const val SESSION_BASELINE_LOOKBACK_WINDOW_MILLIS = 7L * 24L * 60L * 60L * 1000L
        private const val MIN_DISCHARGE_CURRENT_MICRO_AMPS = 20_000
        private const val MIN_RUNTIME_ESTIMATE_MILLIS = 60L * 1000L
        private const val MAX_RUNTIME_ESTIMATE_MILLIS = 14L * 24L * 60L * 60L * 1000L
        private const val MIN_TREND_SAMPLE_COUNT = 2
        private const val MIN_TREND_ELAPSED_MILLIS = 20L * 60L * 1000L
        private const val MIN_TREND_DROP_PERCENT = 1
        private const val DISCHARGE_START_LEVEL_TOLERANCE_MILLIS = 5L * 60L * 1000L
        private const val NORMAL_THRESHOLD_MILLI_AMPS = 1_000f
        private const val FAST_THRESHOLD_MILLI_AMPS = 2_500f
        private const val ULTRA_FAST_THRESHOLD_MILLI_AMPS = 4_500f
        private const val SUPER_VOOC_ESTIMATE_THRESHOLD_MILLI_AMPS = 6_000f
        private const val HEALTH_EXCELLENT_THRESHOLD = 90
        private const val HEALTH_GOOD_THRESHOLD = 80
        private const val HEALTH_FAIR_THRESHOLD = 70
        private const val HEALTH_POOR_THRESHOLD = 50
    }

    private data class RuntimeEstimate(
        val remainingRuntimeMillis: Long,
        val fullRuntimeMillis: Long,
    )
}
