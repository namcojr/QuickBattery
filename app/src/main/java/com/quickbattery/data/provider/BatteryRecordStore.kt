package com.quickbattery.data.provider

import android.content.Context

internal object BatteryRecordStore {

    /**
     * Persists the longest observed "time since charging stopped" duration and returns the current
     * record. When [candidateMillis] exceeds the stored record it becomes the new record.
     */
    fun updateAndGetLongestSinceLastCharge(
        context: Context,
        candidateMillis: Long?,
    ): Long? {
        val prefs = prefs(context)
        val existing = prefs.getLong(KEY_LONGEST_SINCE_LAST_CHARGE_MILLIS, -1L).takeIf { it > 0L }

        if (candidateMillis != null && candidateMillis > (existing ?: 0L)) {
            prefs.edit()
                .putLong(KEY_LONGEST_SINCE_LAST_CHARGE_MILLIS, candidateMillis)
                .apply()
            return candidateMillis
        }

        return existing
    }

    /**
     * Records the latest full 100 -> 0% runtime projection and returns a smoothed estimate that can
     * be reused as a warm start after the next charge (instead of showing "Learning...").
     *
     * The store keeps a small rolling window of the most recent samples and returns their median,
     * so a single anomalous session (e.g. heavy gaming right before charging) can't skew the warm
     * start. Samples older than [FULL_RUNTIME_ESTIMATE_MAX_AGE_MILLIS] are discarded as stale.
     * A non-null [candidateMillis] is appended only when it differs from the newest stored sample,
     * and the backing preference is written only when the window actually changes, avoiding needless
     * I/O on every refresh.
     */
    fun updateAndGetFullRuntimeEstimate(
        context: Context,
        candidateMillis: Long?,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long? {
        val prefs = prefs(context)

        val stored = parseSamples(prefs.getString(KEY_FULL_RUNTIME_ESTIMATE_SAMPLES, null))
        val window = stored
            .filter { nowMillis - it.timestampMillis in 0L..FULL_RUNTIME_ESTIMATE_MAX_AGE_MILLIS }
            .toMutableList()

        var changed = window.size != stored.size

        if (candidateMillis != null && candidateMillis > 0L &&
            candidateMillis != window.lastOrNull()?.valueMillis
        ) {
            window += RuntimeSample(timestampMillis = nowMillis, valueMillis = candidateMillis)
            changed = true
        }

        while (window.size > MAX_FULL_RUNTIME_ESTIMATE_SAMPLES) {
            window.removeAt(0)
            changed = true
        }

        if (changed) {
            prefs.edit()
                .putString(KEY_FULL_RUNTIME_ESTIMATE_SAMPLES, serializeSamples(window))
                .apply()
        }

        return medianMillis(window)
    }

    private fun medianMillis(samples: List<RuntimeSample>): Long? {
        if (samples.isEmpty()) {
            return null
        }
        val sorted = samples.map { it.valueMillis }.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2
        }
    }

    private fun parseSamples(raw: String?): List<RuntimeSample> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return raw.split(SAMPLE_SEPARATOR).mapNotNull { entry ->
            val parts = entry.split(FIELD_SEPARATOR)
            if (parts.size != 2) return@mapNotNull null
            val timestamp = parts[0].toLongOrNull() ?: return@mapNotNull null
            val value = parts[1].toLongOrNull()?.takeIf { it > 0L } ?: return@mapNotNull null
            RuntimeSample(timestampMillis = timestamp, valueMillis = value)
        }
    }

    private fun serializeSamples(samples: List<RuntimeSample>): String {
        return samples.joinToString(SAMPLE_SEPARATOR) { "${it.timestampMillis}$FIELD_SEPARATOR${it.valueMillis}" }
    }

    private data class RuntimeSample(
        val timestampMillis: Long,
        val valueMillis: Long,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val PREFERENCES_NAME = "battery_record_store"
    private const val KEY_LONGEST_SINCE_LAST_CHARGE_MILLIS = "longest_since_last_charge_millis"
    private const val KEY_FULL_RUNTIME_ESTIMATE_SAMPLES = "full_runtime_estimate_samples"
    private const val MAX_FULL_RUNTIME_ESTIMATE_SAMPLES = 5
    private const val FULL_RUNTIME_ESTIMATE_MAX_AGE_MILLIS = 14L * 24L * 60L * 60L * 1000L
    private const val SAMPLE_SEPARATOR = ";"
    private const val FIELD_SEPARATOR = ":"
}
