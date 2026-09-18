package com.quickbattery.data.provider

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import java.io.File
import kotlin.math.roundToInt

/**
 * Raw, unit-normalized reads shared by the snapshot provider, [ChargingMeter] and
 * [BatteryDiagnosticsLog], so every consumer interprets the fuel gauge the same way.
 */
internal object BatteryRawReader {

    fun batteryManager(context: Context): BatteryManager? {
        return context.applicationContext.getSystemService(BatteryManager::class.java)
    }

    fun intProperty(manager: BatteryManager?, property: Int): Int? {
        val value = runCatching { manager?.getIntProperty(property) }.getOrNull() ?: return null
        return value.takeUnless { it == Int.MIN_VALUE }
    }

    fun longProperty(manager: BatteryManager?, property: Int): Long? {
        val value = runCatching { manager?.getLongProperty(property) }.getOrNull() ?: return null
        return value.takeUnless { it == Long.MIN_VALUE }
    }

    /**
     * Voltage as reported, in millivolts. Prefers the fuel gauge's live voltage node, then precise
     * vendor extras, and only then EXTRA_VOLTAGE, which OEM builds often quantize: ColorOS reports
     * it in whole volts ("4"), a ~10% error for power, while carrying the real millivolt figure in
     * its own `battery_now_voltage_type` extra.
     *
     * This may be a whole-pack figure on series (2S) packs; see [cellVoltageMillivolts].
     */
    fun reportedVoltageMillivolts(intent: Intent?): Int? {
        readPreciseVoltageMillivolts()?.let { return it }

        VENDOR_MILLI_VOLT_EXTRAS.forEach { key ->
            intent?.getIntExtra(key, Int.MIN_VALUE)
                ?.takeIf { it in PLAUSIBLE_VOLTAGE_MILLI_VOLTS_RANGE }
                ?.let { return it }
        }

        val rawVoltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE) ?: return null
        return normalizeVoltageToMillivolts(rawVoltage)
    }

    /**
     * Per-cell voltage. A lithium cell never exceeds ~4.6 V, so anything above
     * [MAX_SINGLE_CELL_MILLI_VOLTS] is a series pack figure and is divided back down to one cell.
     */
    fun cellVoltageMillivolts(reportedMillivolts: Int?): Int? {
        val reported = reportedMillivolts?.takeIf { it > 0 } ?: return null
        val cells = seriesCellsFromVoltage(reported) ?: return reported
        return reported / cells
    }

    /** Series cell count implied by a pack-level voltage, or null for a single-cell reading. */
    fun seriesCellsFromVoltage(reportedMillivolts: Int?): Int? {
        val reported = reportedMillivolts?.takeIf { it > MAX_SINGLE_CELL_MILLI_VOLTS } ?: return null
        return (reported / NOMINAL_CELL_MILLI_VOLTS)
            .roundToInt()
            .coerceIn(2, MAX_SERIES_CELL_COUNT)
    }

    /**
     * Charge counter in microamp-hours. Some ROMs report it in mAh; a value that small at a
     * non-trivial level cannot be µAh for any phone battery, so it is scaled up.
     */
    fun chargeCounterMicroAmpHours(manager: BatteryManager?, levelPercent: Int?): Long? {
        val raw = intProperty(manager, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.toLong()
            ?.takeIf { it > 0L }
            ?: return null
        val looksLikeMilliAmpHours = raw < MAX_MILLI_AMP_HOUR_COUNTER &&
            (levelPercent ?: 0) >= MIN_LEVEL_FOR_COUNTER_UNIT_CHECK
        return if (looksLikeMilliAmpHours) raw * MICRO_PER_MILLI else raw
    }

    fun readSmallTextFile(file: File, maxChars: Int = 256): String? {
        return runCatching {
            if (!file.canRead()) return@runCatching null
            file.bufferedReader().use { reader ->
                val buffer = CharArray(maxChars)
                val read = reader.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read).trim()
            }
        }.getOrNull()
    }

    private fun readPreciseVoltageMillivolts(): Int? {
        VOLTAGE_NOW_SYSFS_PATHS.forEach { path ->
            val raw = readSmallTextFile(File(path))?.toIntOrNull() ?: return@forEach
            // voltage_now is documented in microvolts; some kernels report millivolts directly.
            val normalized = normalizeVoltageToMillivolts(raw)
            if (normalized != null && normalized in PLAUSIBLE_VOLTAGE_MILLI_VOLTS_RANGE) {
                return normalized
            }
        }
        return null
    }

    private fun normalizeVoltageToMillivolts(rawVoltage: Int): Int? {
        if (rawVoltage <= 0) {
            return null
        }

        // Android documents EXTRA_VOLTAGE as millivolts, but some OEM builds expose V / dV / cV
        // or even microvolts. Normalize common variants so UI and power math stay correct.
        return when {
            rawVoltage in PLAUSIBLE_VOLTAGE_MILLI_VOLTS_RANGE -> rawVoltage
            rawVoltage in PLAUSIBLE_VOLTAGE_VOLTS_RANGE -> rawVoltage * 1_000
            rawVoltage in PLAUSIBLE_VOLTAGE_DECI_VOLTS_RANGE -> rawVoltage * 100
            rawVoltage in PLAUSIBLE_VOLTAGE_CENTI_VOLTS_RANGE -> rawVoltage * 10
            rawVoltage in PLAUSIBLE_VOLTAGE_MICRO_VOLTS_RANGE -> rawVoltage / 1_000
            else -> rawVoltage
        }
    }

    const val MAX_SERIES_CELL_COUNT = 4

    private const val MAX_SINGLE_CELL_MILLI_VOLTS = 5_500
    private const val NOMINAL_CELL_MILLI_VOLTS = 3_900.0
    private const val MICRO_PER_MILLI = 1_000L
    // 100 mAh expressed in µAh: no phone battery holds less than that above a few percent.
    private const val MAX_MILLI_AMP_HOUR_COUNTER = 100_000L
    private const val MIN_LEVEL_FOR_COUNTER_UNIT_CHECK = 5

    private val PLAUSIBLE_VOLTAGE_VOLTS_RANGE = 2..20
    private val PLAUSIBLE_VOLTAGE_DECI_VOLTS_RANGE = 20..200
    private val PLAUSIBLE_VOLTAGE_CENTI_VOLTS_RANGE = 200..2_000
    private val PLAUSIBLE_VOLTAGE_MILLI_VOLTS_RANGE = 2_000..20_000
    private val PLAUSIBLE_VOLTAGE_MICRO_VOLTS_RANGE = 2_000_000..20_000_000

    // Vendor battery-broadcast extras carrying the precise voltage in millivolts (OPPO/OnePlus
    // ColorOS). "min" is the lower cell on dual-cell packs, so "now" is preferred.
    private val VENDOR_MILLI_VOLT_EXTRAS = listOf(
        "battery_now_voltage_type",
        "battery_min_voltage_type",
    )

    private val VOLTAGE_NOW_SYSFS_PATHS = listOf(
        "/sys/class/power_supply/battery/voltage_now",
        "/sys/class/power_supply/bms/voltage_now",
    )
}
