package com.quickbattery.data.provider

import android.content.Context
import com.quickbattery.domain.model.BatterySnapshot
import com.quickbattery.domain.model.BatteryStatus

internal object BatteryLevelHistoryStore {

    // Guards read-modify-write across concurrent callers (foreground service on the main thread,
    // provider on a background dispatcher). Without it, interleaved appends drop samples.
    private val lock = Any()

    fun clear(context: Context) = synchronized(lock) {
        prefs(context)
            .edit()
            .remove(KEY_LEVEL_SAMPLES)
            .apply()
    }

    fun appendSnapshotSample(
        context: Context,
        snapshot: BatterySnapshot,
    ) = synchronized(lock) {
        val level = snapshot.levelPercent ?: return@synchronized
        val now = snapshot.timestampMillis

        val existing = readSamples(context).toMutableList()
        val last = existing.lastOrNull()

        if (last != null) {
            if (now <= last.timestampMillis) {
                return@synchronized
            }

            val unchanged =
                last.levelPercent == level &&
                    last.status == snapshot.status &&
                    now - last.timestampMillis < MIN_SAMPLE_INTERVAL_MILLIS
            if (unchanged) {
                return@synchronized
            }
        }

        appendPruneWrite(
            context = context,
            existing = existing,
            sample = BatteryLevelSample(
                timestampMillis = now,
                levelPercent = level,
                status = snapshot.status,
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
        val last = existing.lastOrNull()
        if (last != null && timestampMillis <= last.timestampMillis) {
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

        val cutoff = sample.timestampMillis - HISTORY_RETENTION_MILLIS
        val pruned = existing
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

        prefs(context)
            .edit()
            .putString(KEY_LEVEL_SAMPLES, serialized)
            .apply()
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
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val PREFERENCES_NAME = "battery_level_history"
    private const val KEY_LEVEL_SAMPLES = "samples"
    private const val SAMPLE_SEPARATOR = ";"
    private const val FIELD_SEPARATOR = ","
    private const val HISTORY_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L
    private const val MIN_SAMPLE_INTERVAL_MILLIS = 60L * 1000L
    private const val MAX_SAMPLES = 512
}