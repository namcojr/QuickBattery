package com.quickbattery.data.provider

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Turns the fuel gauge's raw, OEM-dependent current figure into a trustworthy one.
 *
 * BATTERY_PROPERTY_CURRENT_NOW is documented in microamps, but OEM builds disagree on both unit
 * (µA vs mA) and meaning: on dual-cell SuperVOOC/Warp packs it may be the real pack current or a
 * single-cell equivalent twice as large. No API says which, and the sysfs nodes that would reveal
 * the wiring are SELinux-blocked for apps. What *is* consistent is the charge counter: it is kept in
 * the same units as the advertised capacity and battery percentage. So while charging, the rate at
 * which the counter climbs is a ground-truth current, and comparing it with the raw register
 * reveals the register's scale factor exactly. That factor is learned once and persisted; from
 * then on every raw reading (charging or not) is converted into capacity-equivalent microamps,
 * for which power is simply I x per-cell voltage. The factor is signed, so a ROM that reports
 * charging current as negative (ColorOS does) is also brought back to Android's convention of
 * positive while charging.
 *
 * Samples come from the monitor service (every few seconds while plugged in), from every
 * ACTION_BATTERY_CHANGED, and from snapshot reads. The buffer lives in memory only.
 */
internal object ChargingMeter {

    data class Sample(
        val wallTimeMillis: Long,
        val elapsedMillis: Long,
        val source: String,
        val plugged: Boolean?,
        val pluggedType: Int,
        val statusCode: Int,
        val levelPercent: Int?,
        val rawCurrentNow: Int?,
        val rawCurrentAverage: Int?,
        val chargeCounterMicroAmpHours: Long?,
        val reportedVoltageMillivolts: Int?,
        val temperatureDeciCelsius: Int?,
    )

    enum class PowerSource {
        /** Smoothed live current scaled by the learned factor. */
        Calibrated,

        /** Averaged from the charge counter's climb while no factor has been learned yet. */
        ChargeCounter,
    }

    data class Reading(
        /**
         * Capacity-equivalent current (median of the last few seconds). Positive while charging
         * once calibrated; sign as reported before that.
         */
        val currentMicroAmps: Int?,
        val averageCurrentMicroAmps: Int?,
        val cellVoltageMillivolts: Int?,
        val seriesCellCount: Int?,
        val chargingPowerMilliWatts: Int?,
        val powerSource: PowerSource?,
        val calibrationFactor: Double?,
        val counterRateMicroAmps: Long?,
    )

    private val lock = Any()
    private val buffer = ArrayDeque<Sample>()

    private var prefsLoaded = false
    private var calibrationFactor: Double? = null
    private var pendingFactor: Double? = null
    private var microAmpUnitsConfirmed = false
    private var calibrationAnchorElapsedMillis = 0L

    /** Reads the gauge now, records the sample, and advances calibration. */
    fun sample(
        context: Context,
        batteryIntent: Intent?,
        source: String,
    ): Sample {
        val manager = BatteryRawReader.batteryManager(context)
        val level = BatteryEventRecorder.readLevelPercent(batteryIntent)
        val sample = Sample(
            wallTimeMillis = System.currentTimeMillis(),
            elapsedMillis = SystemClock.elapsedRealtime(),
            source = source,
            plugged = BatteryEventRecorder.readPlugged(batteryIntent),
            pluggedType = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1,
            statusCode = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1,
            levelPercent = level,
            rawCurrentNow = BatteryRawReader.intProperty(manager, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            rawCurrentAverage = BatteryRawReader.intProperty(manager, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            chargeCounterMicroAmpHours = BatteryRawReader.chargeCounterMicroAmpHours(manager, level),
            reportedVoltageMillivolts = BatteryRawReader.reportedVoltageMillivolts(batteryIntent),
            temperatureDeciCelsius = batteryIntent
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeUnless { it == Int.MIN_VALUE },
        )

        synchronized(lock) {
            ensureLoaded(context)
            append(sample)
            observeUnits(context, sample)
            if (sample.isCharging()) {
                evaluateCalibration(context)
            }
        }
        return sample
    }

    /** Best current / power figures derived from the recent samples. */
    fun resolve(context: Context): Reading = synchronized(lock) {
        ensureLoaded(context)
        val latest = buffer.lastOrNull() ?: return@synchronized EMPTY_READING

        val cellVoltage = BatteryRawReader.cellVoltageMillivolts(latest.reportedVoltageMillivolts)
        val recentRaw = buffer
            .filter { latest.elapsedMillis - it.elapsedMillis <= SMOOTHING_WINDOW_MILLIS }
            .mapNotNull { it.rawCurrentNow }
        val current = medianOf(recentRaw)?.let { scaleRaw(it, latest) }
        val average = latest.rawCurrentAverage?.let { scaleRaw(it, latest) }

        val charging = latest.isCharging()
        val counterRate = if (charging) {
            counterRateMicroAmps(
                buffer.filter {
                    it.plugged == true && latest.elapsedMillis - it.elapsedMillis <= COUNTER_WINDOW_MILLIS
                },
            )?.rateMicroAmps?.takeIf { it > 0L }
        } else {
            null
        }

        val factor = calibrationFactor
        val (power, source) = when {
            !charging || cellVoltage == null -> null to null
            factor != null && current != null ->
                milliWatts(abs(current.toLong()), cellVoltage) to PowerSource.Calibrated
            counterRate != null -> milliWatts(counterRate, cellVoltage) to PowerSource.ChargeCounter
            else -> null to null
        }

        Reading(
            currentMicroAmps = current,
            averageCurrentMicroAmps = average,
            cellVoltageMillivolts = cellVoltage,
            seriesCellCount = BatteryRawReader.seriesCellsFromVoltage(latest.reportedVoltageMillivolts)
                ?: factor?.let(::seriesCellsFromFactor),
            chargingPowerMilliWatts = power?.takeIf { it > 0 },
            powerSource = source,
            calibrationFactor = factor,
            counterRateMicroAmps = counterRate,
        )
    }

    fun clearCalibration(context: Context) = synchronized(lock) {
        prefs(context).edit().clear().apply()
        calibrationFactor = null
        pendingFactor = null
        microAmpUnitsConfirmed = false
        calibrationAnchorElapsedMillis = 0L
        prefsLoaded = true
    }

    private fun append(sample: Sample) {
        // A plug transition starts a fresh window: charge and discharge samples must never be
        // blended into one median or one counter slope.
        val previous = buffer.lastOrNull()
        if (previous != null && previous.plugged != sample.plugged) {
            buffer.clear()
            calibrationAnchorElapsedMillis = sample.elapsedMillis
        }
        buffer.addLast(sample)
        while (buffer.isNotEmpty() && sample.elapsedMillis - buffer.first().elapsedMillis > BUFFER_WINDOW_MILLIS) {
            buffer.removeFirst()
        }
    }

    // No mA register can ever read above 50 A, so one large reading proves µA units for good.
    private fun observeUnits(context: Context, sample: Sample) {
        val raw = sample.rawCurrentNow ?: return
        if (!microAmpUnitsConfirmed && abs(raw) > MICRO_AMP_PROOF_THRESHOLD) {
            microAmpUnitsConfirmed = true
            prefs(context).edit().putBoolean(KEY_MICRO_AMPS_CONFIRMED, true).apply()
        }
    }

    /**
     * Compares the raw register with the charge counter over a fresh, non-overlapping window and
     * snaps the ratio to a known scale. A factor is only adopted once two consecutive windows agree,
     * so a single window distorted by a charge-rate step cannot poison it.
     */
    private fun evaluateCalibration(context: Context) {
        val newest = buffer.lastOrNull() ?: return
        // When the counter barely moves (taper, trickle), slide the anchor so windows stay recent.
        calibrationAnchorElapsedMillis = maxOf(
            calibrationAnchorElapsedMillis,
            newest.elapsedMillis - COUNTER_WINDOW_MILLIS,
        )
        val window = buffer.filter { it.elapsedMillis > calibrationAnchorElapsedMillis && it.isCharging() }
        if (window.isEmpty()) {
            return
        }

        val rate = counterRateMicroAmps(window) ?: return
        calibrationAnchorElapsedMillis = rate.endElapsedMillis
        if (rate.rateMicroAmps < MIN_CALIBRATION_RATE_MICRO_AMPS) {
            return
        }

        val rawInSpan = window
            .filter { it.elapsedMillis in rate.startElapsedMillis..rate.endElapsedMillis }
            .mapNotNull { it.rawCurrentNow }
        if (rawInSpan.size < MIN_CALIBRATION_RAW_SAMPLES) {
            return
        }
        // Signed mean: the counter is climbing, so a negative mean means the ROM reports charging
        // current as negative, and the learned factor carries that sign.
        val rawMean = rawInSpan.average().takeIf { it != 0.0 } ?: return

        val ratio = rate.rateMicroAmps / rawMean
        val magnitude = CALIBRATION_CANDIDATES.minBy { abs(ln(abs(ratio) / it)) }
        if (abs(ln(abs(ratio) / magnitude)) > MAX_CALIBRATION_LOG_ERROR) {
            pendingFactor = null
            return
        }
        val candidate = if (ratio < 0.0) -magnitude else magnitude

        if (pendingFactor == candidate) {
            if (calibrationFactor != candidate) {
                calibrationFactor = candidate
                prefs(context).edit().putFloat(KEY_FACTOR, candidate.toFloat()).apply()
            }
        } else {
            pendingFactor = candidate
        }
    }

    private data class CounterRate(
        val rateMicroAmps: Long,
        val startElapsedMillis: Long,
        val endElapsedMillis: Long,
    )

    /**
     * Charge-counter slope measured between the first and last instants the counter *changed*.
     * Anchoring on change points instead of window edges removes the counter's quantization error,
     * which matters on gauges that only update it every few dozen mAh.
     */
    private fun counterRateMicroAmps(samples: List<Sample>): CounterRate? {
        var previousCounter: Long? = null
        var first: Sample? = null
        var last: Sample? = null
        var changes = 0
        samples.forEach { sample ->
            val counter = sample.chargeCounterMicroAmpHours ?: return@forEach
            if (previousCounter != null && counter != previousCounter) {
                if (first == null) first = sample
                last = sample
                changes++
            }
            previousCounter = counter
        }

        val start = first ?: return null
        val end = last ?: return null
        val spanMillis = end.elapsedMillis - start.elapsedMillis
        if (changes < MIN_COUNTER_CHANGES || spanMillis < MIN_COUNTER_SPAN_MILLIS) {
            return null
        }
        val delta = (end.chargeCounterMicroAmpHours ?: return null) - (start.chargeCounterMicroAmpHours ?: return null)
        return CounterRate(
            rateMicroAmps = (delta.toDouble() * MILLIS_PER_HOUR / spanMillis).roundToLong(),
            startElapsedMillis = start.elapsedMillis,
            endElapsedMillis = end.elapsedMillis,
        )
    }

    private fun scaleRaw(raw: Int, context: Sample): Int {
        val factor = calibrationFactor ?: when {
            microAmpUnitsConfirmed || abs(raw) >= MICRO_AMP_PROOF_THRESHOLD -> 1.0
            // Near full a µA register legitimately reads tiny trickle values; don't inflate them.
            context.plugged == true && (context.levelPercent ?: 0) >= TRICKLE_LEVEL_PERCENT -> 1.0
            else -> MICRO_AMPS_PER_MILLI_AMP
        }
        return (raw * factor).coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).roundToInt()
    }

    private fun seriesCellsFromFactor(factor: Double): Int? {
        // A register reading half the capacity-equivalent current is the real current of a 2S pack.
        val magnitude = abs(factor)
        return if (magnitude == 2.0 || magnitude == 2_000.0) 2 else null
    }

    private fun medianOf(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            ((sorted[middle - 1].toLong() + sorted[middle].toLong()) / 2L).toInt()
        }
    }

    // µA x mV = nW; divide by 1e6 for mW.
    private fun milliWatts(microAmps: Long, millivolts: Int): Int {
        return (microAmps.toDouble() * millivolts.toDouble() / 1_000_000.0).roundToInt()
    }

    private fun Sample.isCharging(): Boolean {
        return plugged == true && statusCode == BatteryManager.BATTERY_STATUS_CHARGING
    }

    private fun ensureLoaded(context: Context) {
        if (prefsLoaded) return
        val prefs = prefs(context)
        calibrationFactor = prefs.getFloat(KEY_FACTOR, 0f).takeIf { it != 0f }?.toDouble()
        microAmpUnitsConfirmed = prefs.getBoolean(KEY_MICRO_AMPS_CONFIRMED, false)
        prefsLoaded = true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val EMPTY_READING = Reading(
        currentMicroAmps = null,
        averageCurrentMicroAmps = null,
        cellVoltageMillivolts = null,
        seriesCellCount = null,
        chargingPowerMilliWatts = null,
        powerSource = null,
        calibrationFactor = null,
        counterRateMicroAmps = null,
    )

    // Register-to-true-current multipliers seen in the wild: µA or mA, single-cell-equivalent or
    // real 2S pack current, plus a halved variant for ROMs that double-count the second cell.
    private val CALIBRATION_CANDIDATES = listOf(0.5, 1.0, 2.0, 500.0, 1_000.0, 2_000.0)
    // ±30%: tight enough to tell 1x from 2x (which are 100% apart) with margin for sampling jitter.
    private val MAX_CALIBRATION_LOG_ERROR = ln(1.3)

    private const val PREFS_NAME = "charging_meter_prefs"
    private const val KEY_FACTOR = "calibration_factor"
    private const val KEY_MICRO_AMPS_CONFIRMED = "micro_amps_confirmed"

    private const val MICRO_AMPS_PER_MILLI_AMP = 1_000.0
    private const val MICRO_AMP_PROOF_THRESHOLD = 50_000
    private const val TRICKLE_LEVEL_PERCENT = 97
    private const val MILLIS_PER_HOUR = 3_600_000.0

    private const val SMOOTHING_WINDOW_MILLIS = 30_000L
    private const val COUNTER_WINDOW_MILLIS = 10L * 60L * 1000L
    private const val BUFFER_WINDOW_MILLIS = 15L * 60L * 1000L
    private const val MIN_COUNTER_CHANGES = 3
    private const val MIN_COUNTER_SPAN_MILLIS = 90_000L
    private const val MIN_CALIBRATION_RATE_MICRO_AMPS = 300_000L
    private const val MIN_CALIBRATION_RAW_SAMPLES = 5
}
