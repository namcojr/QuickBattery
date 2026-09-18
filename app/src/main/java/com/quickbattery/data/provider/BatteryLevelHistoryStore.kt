package com.quickbattery.data.provider

import android.content.Context
import com.quickbattery.domain.model.BatteryStatus

@Suppress("ApplySharedPref") // commit() is deliberate; see writeSamples.
internal object BatteryLevelHistoryStore {

    // Guards read-modify-write across concurrent callers (foreground service on the main thread,
    // provider on a background dispatcher). Without it, interleaved appends drop samples.
    private val lock = Any()

    fun clear(context: Context) = synchronized(lock) {
        prefs(context)
            .edit()
            .remove(KEY_LEVEL_SAMPLES)
            .commit()
        Unit
    }

    /**
     * Periodic sample taken while the app is reading the battery. Keeps a heartbeat in the log even
     * when nothing changed, which is what the discharge-trend regression needs.
     *
     * [status] must already be corrected against the plugged flag by
     * [BatteryEventRecorder.effectiveStatus]; a raw FULL recorded just after an unplug would read
     * back as charging evidence and invalidate the session start.
     */
    fun appendPeriodicSample(
        context: Context,
        timestampMillis: Long,
        levelPercent: Int?,
        status: BatteryStatus,
    ) = synchronized(lock) {
        val level = levelPercent?.coerceIn(0, 100) ?: return@synchronized

        val existing = readSamples(context).toMutableList()
        val last = existing.lastOrNull()

        if (last != null) {
            if (timestampMillis <= last.timestampMillis) {
                return@synchronized
            }

            val unchanged =
                last.levelPercent == level &&
                    last.status == status &&
                    timestampMillis - last.timestampMillis < MIN_SAMPLE_INTERVAL_MILLIS
            if (unchanged) {
                return@synchronized
            }
        }

        appendPruneWrite(
            context = context,
            existing = existing,
            sample = BatteryLevelSample(
                timestampMillis = timestampMillis,
                levelPercent = level,
                status = status,
            ),
        )
    }

    /**
     * Appends a raw (level, status) boundary sample at [timestampMillis]. Used for definitive
     * charge/discharge boundaries (power connect/disconnect) that must always be recorded, so
     * charge-cycle detection stays reliable across cold starts.
     */
    fun appendSample(
        context: Context,
        timestampMillis: Long,
        levelPercent: Int,
        status: BatteryStatus,
    ) = synchronized(lock) {
        val level = levelPercent.coerceIn(0, 100)
        val existing = readSamples(context).toMutableList()

        // A boundary sample is never discarded for being out of order. Repair passes replay
        // events at the timestamp they actually happened, which can predate samples already
        // written, and that evidence is exactly what reconstructs a missed transition.
        // appendPruneWrite re-sorts, so an older insert is safe; only an exact duplicate is
        // skipped.
        val duplicate = existing.any {
            it.timestampMillis == timestampMillis && it.levelPercent == level && it.status == status
        }
        if (duplicate) {
            return@synchronized
        }

        appendPruneWrite(
            context = context,
            existing = existing,
            sample = BatteryLevelSample(
                timestampMillis = timestampMillis,
                levelPercent = level,
                status = status,
            ),
        )
    }

    /**
     * Change-based append for the always-on monitor. ACTION_BATTERY_CHANGED fires on
     * voltage/temperature changes too, so recording every event unthrottled would evict the 7-day
     * history within minutes. Only a level or status change (relative to the last sample) is
     * persisted, keeping the log compact and retention-friendly while preserving every transition.
     */
    fun appendSampleIfChanged(
        context: Context,
        timestampMillis: Long,
        levelPercent: Int,
        status: BatteryStatus,
    ) = synchronized(lock) {
        val level = levelPercent.coerceIn(0, 100)
        val existing = readSamples(context).toMutableList()
        val last = existing.lastOrNull()
        if (last != null) {
            if (timestampMillis <= last.timestampMillis) {
                return@synchronized
            }
            if (last.levelPercent == level && last.status == status) {
                return@synchronized
            }
        }

        appendPruneWrite(
            context = context,
            existing = existing,
            sample = BatteryLevelSample(
                timestampMillis = timestampMillis,
                levelPercent = level,
                status = status,
            ),
        )
    }

    private fun appendPruneWrite(
        context: Context,
        existing: MutableList<BatteryLevelSample>,
        sample: BatteryLevelSample,
    ) {
        existing += sample

        // Sort before pruning: a repaired boundary can be inserted out of order, and pruning an
        // unsorted list would evict the newest samples instead of the oldest.
        val sorted = existing.sortedBy { it.timestampMillis }
        val cutoff = sorted.last().timestampMillis - HISTORY_RETENTION_MILLIS
        val pruned = sorted
            .filter { it.timestampMillis >= cutoff }
            .takeLast(MAX_SAMPLES)

        writeSamples(context, pruned)
    }

    fun getRecentSamples(
        context: Context,
        lookbackWindowMillis: Long,
    ): List<BatteryLevelSample> {
        if (lookbackWindowMillis <= 0L) {
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val cutoff = now - lookbackWindowMillis
        return readSamples(context)
            .asSequence()
            .filter { it.timestampMillis in cutoff..now }
            .sortedBy { it.timestampMillis }
            .toList()
    }

    private fun readSamples(context: Context): List<BatteryLevelSample> {
        val raw = prefs(context).getString(KEY_LEVEL_SAMPLES, null) ?: return emptyList()

        return raw
            .split(SAMPLE_SEPARATOR)
            .asSequence()
            .mapNotNull { parseSample(it) }
            .sortedBy { it.timestampMillis }
            .toList()
    }

    private fun writeSamples(
        context: Context,
        samples: List<BatteryLevelSample>,
    ) {
        val serialized = samples.joinToString(separator = SAMPLE_SEPARATOR) { sample ->
            listOf(
                sample.timestampMillis.toString(),
                sample.levelPercent.toString(),
                sample.status.name,
            ).joinToString(separator = FIELD_SEPARATOR)
        }

        // Committed rather than applied: these writes happen while handling a power broadcast,
        // and an unflushed apply() is lost if the OEM kills the process straight afterwards.
        prefs(context)
            .edit()
            .putString(KEY_LEVEL_SAMPLES, serialized)
            .commit()
    }

    private fun parseSample(raw: String): BatteryLevelSample? {
        val fields = raw.split(FIELD_SEPARATOR)
        if (fields.size != 3) {
            return null
        }

        val timestampMillis = fields[0].toLongOrNull() ?: return null
        val levelPercent = fields[1].toIntOrNull()?.coerceIn(0, 100) ?: return null
        val status = runCatching { BatteryStatus.valueOf(fields[2]) }.getOrNull() ?: return null

        return BatteryLevelSample(
            timestampMillis = timestampMillis,
            levelPercent = levelPercent,
            status = status,
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val PREFERENCES_NAME = "battery_level_history"
    private const val KEY_LEVEL_SAMPLES = "samples"
    private const val SAMPLE_SEPARATOR = ";"
    private const val FIELD_SEPARATOR = ","
    private const val HISTORY_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L
    private const val MIN_SAMPLE_INTERVAL_MILLIS = 60L * 1000L
    private const val MAX_SAMPLES = 512
}