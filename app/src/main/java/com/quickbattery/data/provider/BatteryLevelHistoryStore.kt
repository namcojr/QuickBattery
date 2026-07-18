package com.quickbattery.data.provider

import android.content.Context
import com.quickbattery.domain.model.BatterySnapshot
import com.quickbattery.domain.model.BatteryStatus

internal object BatteryLevelHistoryStore {

    fun appendSnapshotSample(
        context: Context,
        snapshot: BatterySnapshot,
    ) {
        val level = snapshot.levelPercent ?: return
        val now = snapshot.timestampMillis

        val existing = readSamples(context).toMutableList()
        val last = existing.lastOrNull()

        if (last != null) {
            if (now <= last.timestampMillis) {
                return
            }

            val unchanged =
                last.levelPercent == level &&
                    last.status == snapshot.status &&
                    now - last.timestampMillis < MIN_SAMPLE_INTERVAL_MILLIS
            if (unchanged) {
                return
            }
        }

        existing += BatteryLevelSample(
            timestampMillis = now,
            levelPercent = level,
            status = snapshot.status,
        )

        val cutoff = now - HISTORY_RETENTION_MILLIS
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