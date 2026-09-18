package com.quickbattery.data.provider

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Opt-in diagnostic capture for devices whose gauge readings don't add up (dual-cell packs, OEM
 * unit quirks). While enabled it writes:
 *
 *  - `device.txt`: build info, every BatteryManager property, the full ACTION_BATTERY_CHANGED
 *    extras, and which power_supply / oplus_chg sysfs nodes this app can actually read.
 *  - `samples.csv`: one row per [ChargingMeter] sample with raw and resolved figures, plus any
 *    readable numeric sysfs nodes as extra columns.
 *  - `broadcasts.log`: every ACTION_BATTERY_CHANGED with all of its extras, to expose vendor keys.
 *
 * [export] zips it all to a user-chosen document, after which logging stops and the files go.
 */
internal object BatteryDiagnosticsLog {

    private val lock = Any()
    private val enabledState = MutableStateFlow(false)
    private var loaded = false
    private var sysfsColumns: List<File> = emptyList()

    fun enabled(context: Context): StateFlow<Boolean> {
        ensureLoaded(context)
        return enabledState.asStateFlow()
    }

    fun isEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return enabledState.value
    }

    /** Starts a fresh capture, discarding any previous one. */
    fun enable(context: Context) {
        synchronized(lock) {
            val dir = logDir(context)
            dir.deleteRecursively()
            dir.mkdirs()

            sysfsColumns = discoverNumericSysfsNodes()
            File(dir, DEVICE_FILE).writeText(buildDeviceReport(context, title = "Logging started"))
            File(dir, SAMPLES_FILE).writeText(csvHeader() + "\n")

            prefs(context).edit().putBoolean(KEY_ENABLED, true).commit()
            enabledState.value = true
        }
        // The service owns the sampling loop; (re)starting it makes it pick up the new state.
        BatteryMonitorService.start(context)
    }

    /** Stops capturing and deletes whatever was captured. */
    fun disable(context: Context) = synchronized(lock) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false).commit()
        enabledState.value = false
        sysfsColumns = emptyList()
        logDir(context).deleteRecursively()
        Unit
    }

    fun recordSample(
        context: Context,
        sample: ChargingMeter.Sample,
        reading: ChargingMeter.Reading,
    ) {
        if (!isEnabled(context)) return
        synchronized(lock) {
            if (!enabledState.value) return
            val file = File(logDir(context), SAMPLES_FILE)
            if (file.length() > MAX_FILE_BYTES) return

            val row = buildList {
                add(timestampFormat().format(Date(sample.wallTimeMillis)))
                add("%.3f".format(Locale.US, sample.elapsedMillis / 1000.0))
                add(sample.source)
                add(sample.plugged?.toString().orEmpty())
                add(sample.pluggedType.toString())
                add(sample.statusCode.toString())
                add(sample.levelPercent?.toString().orEmpty())
                add(sample.rawCurrentNow?.toString().orEmpty())
                add(sample.rawCurrentAverage?.toString().orEmpty())
                add(sample.chargeCounterMicroAmpHours?.toString().orEmpty())
                add(sample.reportedVoltageMillivolts?.toString().orEmpty())
                add(reading.cellVoltageMillivolts?.toString().orEmpty())
                add(sample.temperatureDeciCelsius?.toString().orEmpty())
                add(reading.currentMicroAmps?.toString().orEmpty())
                add(reading.counterRateMicroAmps?.toString().orEmpty())
                add(reading.chargingPowerMilliWatts?.toString().orEmpty())
                add(reading.powerSource?.name.orEmpty())
                add(reading.calibrationFactor?.toString().orEmpty())
                add(reading.seriesCellCount?.toString().orEmpty())
                sysfsColumns.forEach { node -> add(BatteryRawReader.readSmallTextFile(node, 64).orEmpty()) }
            }
            runCatching { file.appendText(row.joinToString(",") { it.csvEscaped() } + "\n") }
        }
    }

    fun recordBroadcast(context: Context, intent: Intent?) {
        if (intent == null || !isEnabled(context)) return
        synchronized(lock) {
            if (!enabledState.value) return
            val file = File(logDir(context), BROADCASTS_FILE)
            if (file.length() > MAX_FILE_BYTES) return
            val line = timestampFormat().format(Date()) + " " + describeExtras(intent)
            runCatching { file.appendText(line + "\n") }
        }
    }

    /**
     * Writes the capture as a zip to [uri], with a closing device report so the start and end state
     * can be compared. Logging stops and the files are deleted only once the zip is fully written.
     */
    fun export(context: Context, uri: Uri): Boolean {
        val written = synchronized(lock) {
            val dir = logDir(context)
            if (!dir.exists()) return@synchronized false
            runCatching {
                File(dir, DEVICE_END_FILE).writeText(buildDeviceReport(context, title = "Logging exported"))
                val output = context.contentResolver.openOutputStream(uri) ?: error("No output stream")
                ZipOutputStream(output.buffered()).use { zip ->
                    dir.listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }.isSuccess
        }
        if (written) {
            disable(context)
        }
        return written
    }

    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "quickbattery-log-${Build.MODEL.replace(Regex("[^A-Za-z0-9]+"), "_")}-$stamp.zip"
    }

    private fun buildDeviceReport(context: Context, title: String): String = buildString {
        appendLine("QuickBattery diagnostics - $title")
        appendLine("Time: ${timestampFormat().format(Date())}")
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        appendLine("App version: $versionName")
        appendLine()

        appendLine("[Device]")
        appendLine("Manufacturer: ${Build.MANUFACTURER}")
        appendLine("Brand: ${Build.BRAND}")
        appendLine("Model: ${Build.MODEL}")
        appendLine("Device: ${Build.DEVICE}")
        appendLine("Product: ${Build.PRODUCT}")
        appendLine("Hardware: ${Build.HARDWARE}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appendLine("SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        }
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Build: ${Build.DISPLAY}")
        appendLine("Fingerprint: ${Build.FINGERPRINT}")
        appendLine()

        appendLine("[BatteryManager properties]")
        val manager = BatteryRawReader.batteryManager(context)
        BatteryManager::class.java.fields
            .filter { it.name.startsWith("BATTERY_PROPERTY_") }
            .sortedBy { it.name }
            .forEach { field ->
                val id = runCatching { field.getInt(null) }.getOrNull() ?: return@forEach
                val asInt = runCatching { manager?.getIntProperty(id) }
                val asLong = runCatching { manager?.getLongProperty(id) }
                appendLine(
                    "${field.name} ($id): int=${asInt.getOrNull() ?: asInt.exceptionOrNull()?.javaClass?.simpleName}" +
                        " long=${asLong.getOrNull() ?: asLong.exceptionOrNull()?.javaClass?.simpleName}",
                )
            }
        appendLine("isCharging: ${runCatching { manager?.isCharging }.getOrNull()}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            appendLine("computeChargeTimeRemaining: ${runCatching { manager?.computeChargeTimeRemaining() }.getOrNull()}")
        }
        appendLine()

        appendLine("[ACTION_BATTERY_CHANGED extras]")
        val sticky = BatteryEventRecorder.readBatteryIntent(context)
        sticky?.extras?.let { extras ->
            extras.keySet().sorted().forEach { key ->
                @Suppress("DEPRECATION")
                val value = extras.get(key)
                appendLine("$key = $value (${value?.javaClass?.simpleName})")
            }
        } ?: appendLine("<unavailable>")
        appendLine()

        appendLine("[Meter]")
        appendLine(ChargingMeter.resolve(context).toString())
        appendLine()

        SYSFS_ROOTS.forEach { root ->
            appendLine("[$root]")
            val supplies = runCatching { File(root).listFiles() }.getOrNull()
            if (supplies == null) {
                appendLine("<cannot list>")
            } else {
                supplies.sortedBy { it.name }.forEach { supply ->
                    appendLine("-- ${supply.name}")
                    val nodes = runCatching { supply.listFiles() }.getOrNull()
                    if (nodes == null) {
                        appendLine("   <cannot list>")
                        return@forEach
                    }
                    nodes.filter { it.isFile }.sortedBy { it.name }.forEach { node ->
                        val value = when {
                            !node.canRead() -> "<denied>"
                            else -> BatteryRawReader.readSmallTextFile(node)?.replace("\n", " | ") ?: "<read failed>"
                        }
                        appendLine("   ${node.name} = $value")
                    }
                }
            }
            appendLine()
        }
    }

    /** Readable sysfs nodes worth sampling over time (currents, voltages, power, charger state). */
    private fun discoverNumericSysfsNodes(): List<File> {
        return SYSFS_ROOTS
            .flatMap { root -> runCatching { File(root).listFiles()?.toList() }.getOrNull().orEmpty() }
            .sortedBy { it.name }
            .flatMap { supply -> runCatching { supply.listFiles()?.toList() }.getOrNull().orEmpty() }
            .filter { node ->
                node.isFile &&
                    INTERESTING_NODE_PATTERN.containsMatchIn(node.name) &&
                    BatteryRawReader.readSmallTextFile(node, 64)?.toLongOrNull() != null
            }
            .take(MAX_SYSFS_COLUMNS)
    }

    private fun csvHeader(): String {
        val fixed = listOf(
            "time", "elapsed_s", "source", "plugged", "plug_type", "status", "level",
            "current_now_raw", "current_avg_raw", "charge_counter_uah", "voltage_reported_mv",
            "voltage_cell_mv", "temp_decic", "current_resolved_ua", "counter_rate_ua",
            "power_mw", "power_source", "cal_factor", "series_cells",
        )
        val dynamic = sysfsColumns.map { "${it.parentFile?.name}/${it.name}" }
        return (fixed + dynamic).joinToString(",") { it.csvEscaped() }
    }

    private fun describeExtras(intent: Intent): String {
        val extras = intent.extras ?: return "<no extras>"
        return extras.keySet().sorted().joinToString(" ") { key ->
            @Suppress("DEPRECATION")
            "$key=${extras.get(key)}"
        }
    }

    private fun String.csvEscaped(): String {
        return if (any { it == ',' || it == '"' || it == '\n' }) "\"" + replace("\"", "\"\"") + "\"" else this
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val enabled = prefs(context).getBoolean(KEY_ENABLED, false)
            if (enabled) {
                // Process restart mid-capture: columns must match the header already on disk, so
                // rediscover them the same way.
                sysfsColumns = discoverNumericSysfsNodes()
            }
            enabledState.value = enabled
            loaded = true
        }
    }

    private fun timestampFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    private fun logDir(context: Context) = File(context.applicationContext.filesDir, LOG_DIR)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "diagnostics_prefs"
    private const val KEY_ENABLED = "logging_enabled"
    private const val LOG_DIR = "diagnostics"
    private const val DEVICE_FILE = "device.txt"
    private const val DEVICE_END_FILE = "device-end.txt"
    private const val SAMPLES_FILE = "samples.csv"
    private const val BROADCASTS_FILE = "broadcasts.log"
    private const val MAX_FILE_BYTES = 25L * 1024L * 1024L
    private const val MAX_SYSFS_COLUMNS = 40

    private val SYSFS_ROOTS = listOf("/sys/class/power_supply", "/sys/class/oplus_chg")
    private val INTERESTING_NODE_PATTERN =
        Regex("current|voltage|power|charge_counter|temp|ibat|vbat|ichg|vchg|vooc|fast|chg_type|charger", RegexOption.IGNORE_CASE)
}
