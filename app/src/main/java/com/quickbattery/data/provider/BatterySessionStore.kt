package com.quickbattery.data.provider

import android.content.Context
import com.quickbattery.domain.model.BatteryStatus

/**
 * Persisted charge/discharge session boundary.
 *
 * The authoritative signal here is the *plugged* flag (BatteryManager.EXTRA_PLUGGED), not
 * BATTERY_STATUS_*. A phone can report NOT_CHARGING or FULL while still sitting on the charger
 * (charge limiting, thermal throttling, trickle top-up), so status alone cannot tell "on battery"
 * from "plugged in but idle". EXTRA_PLUGGED is the hardware truth and is what starts and ends a
 * discharge session.
 *
 * Each boundary also carries how it was established. A timestamp taken from a real broadcast is
 * exact; one reconstructed afterwards by [BatteryEventRecorder.reconcile] is an estimate. Keeping
 * them apart is what lets a repair pass fill a gap without ever overwriting something better.
 *
 * Every write commits rather than applies. These writes happen inside a BroadcastReceiver on an OEM
 * build that may freeze or kill the process the moment onReceive returns; an async apply() that has
 * not been flushed is simply lost, and a lost unplug timestamp costs a whole session.
 */
@Suppress("ApplySharedPref") // commit() is deliberate here; see the class comment.
internal object BatterySessionStore {

    // Serializes read-modify-write across the monitor service (main thread), the manifest receiver
    // (main thread, separate process wake-up) and the provider (background dispatcher) so a
    // discharge-session start can't be clobbered by an interleaved update.
    private val lock = Any()

    /** Everything known about the current session, read as one consistent unit. */
    data class SessionState(
        val dischargeStartedAtMillis: Long?,
        /** True when the session start came from a broadcast rather than being reconstructed. */
        val dischargeStartIsExact: Boolean,
        val plugged: Boolean?,
        val pluggedUpdatedAtMillis: Long?,
        /** True when the plugged state was last set by a broadcast rather than a repair pass. */
        val pluggedFromBroadcast: Boolean,
        val lastStatus: BatteryStatus?,
        val lastStatusUpdatedAtMillis: Long?,
    )

    fun read(context: Context): SessionState = synchronized(lock) { readLocked(context) }

    fun clear(context: Context) = synchronized(lock) {
        prefs(context)
            .edit()
            .remove(KEY_DISCHARGING_STARTED_AT_MILLIS)
            .remove(KEY_DISCHARGE_START_EXACT)
            .remove(KEY_LAST_STATUS)
            .remove(KEY_LAST_STATUS_UPDATED_AT_MILLIS)
            .remove(KEY_PLUGGED)
            .remove(KEY_PLUGGED_UPDATED_AT_MILLIS)
            .remove(KEY_PLUGGED_FROM_BROADCAST)
            .commit()
        Unit
    }

    /**
     * Opens a discharge session at [timestampMillis].
     *
     * An open session is not restarted by a repeat of the same event - ColorOS does redeliver
     * queued broadcasts once it unfreezes an app, and treating that as a fresh unplug would reset
     * the clock mid-session. The one case that does overwrite is an [exact] timestamp arriving for
     * a session that was only estimated, which strictly improves what is stored.
     *
     * @return the session start actually in effect afterwards.
     */
    fun beginDischargeSession(
        context: Context,
        timestampMillis: Long,
        exact: Boolean,
    ): Long = synchronized(lock) {
        val current = readLocked(context)
        val existingStart = current.dischargeStartedAtMillis
        val sessionAlreadyOpen = current.plugged == false && existingStart != null

        val upgradesEstimate = sessionAlreadyOpen && exact && !current.dischargeStartIsExact
        val (start, startIsExact) = when {
            upgradesEstimate -> timestampMillis to true
            sessionAlreadyOpen -> existingStart!! to current.dischargeStartIsExact
            else -> timestampMillis to exact
        }

        prefs(context)
            .edit()
            .putLong(KEY_DISCHARGING_STARTED_AT_MILLIS, start)
            .putBoolean(KEY_DISCHARGE_START_EXACT, startIsExact)
            .putString(KEY_LAST_STATUS, BatteryStatus.Discharging.name)
            .putLong(KEY_LAST_STATUS_UPDATED_AT_MILLIS, timestampMillis)
            .putBoolean(KEY_PLUGGED, false)
            .putLong(KEY_PLUGGED_UPDATED_AT_MILLIS, timestampMillis)
            .putBoolean(KEY_PLUGGED_FROM_BROADCAST, exact)
            .commit()

        start
    }

    /**
     * Closes the discharge session at [timestampMillis] and hands back the start that was open, so
     * the caller can turn it into a completed-session duration before it is discarded.
     *
     * @return the session start that was cleared, or null when no session was open.
     */
    fun endDischargeSession(
        context: Context,
        timestampMillis: Long,
        exact: Boolean,
    ): Long? = synchronized(lock) {
        val start = readLocked(context).dischargeStartedAtMillis

        prefs(context)
            .edit()
            .remove(KEY_DISCHARGING_STARTED_AT_MILLIS)
            .remove(KEY_DISCHARGE_START_EXACT)
            .putString(KEY_LAST_STATUS, BatteryStatus.Charging.name)
            .putLong(KEY_LAST_STATUS_UPDATED_AT_MILLIS, timestampMillis)
            .putBoolean(KEY_PLUGGED, true)
            .putLong(KEY_PLUGGED_UPDATED_AT_MILLIS, timestampMillis)
            .putBoolean(KEY_PLUGGED_FROM_BROADCAST, exact)
            .commit()

        start
    }

    /**
     * Records an observed state without touching the session boundary.
     *
     * Used for every routine battery reading. Boundary changes are detected by comparing the
     * plugged flag and applied through [beginDischargeSession] / [endDischargeSession], so an
     * ordinary sample can never move the session start.
     */
    fun recordObservation(
        context: Context,
        status: BatteryStatus,
        plugged: Boolean,
        timestampMillis: Long,
        fromBroadcast: Boolean,
    ) = synchronized(lock) {
        prefs(context)
            .edit()
            .putString(KEY_LAST_STATUS, status.name)
            .putLong(KEY_LAST_STATUS_UPDATED_AT_MILLIS, timestampMillis)
            .putBoolean(KEY_PLUGGED, plugged)
            .putLong(KEY_PLUGGED_UPDATED_AT_MILLIS, timestampMillis)
            .putBoolean(KEY_PLUGGED_FROM_BROADCAST, fromBroadcast)
            .commit()
        Unit
    }

    fun getLastDischargingStartMillis(context: Context): Long? =
        read(context).dischargeStartedAtMillis

    private fun readLocked(context: Context): SessionState {
        val prefs = prefs(context)
        val rawStatus = prefs.getString(KEY_LAST_STATUS, null)
        return SessionState(
            dischargeStartedAtMillis = prefs
                .getLong(KEY_DISCHARGING_STARTED_AT_MILLIS, -1L)
                .takeIf { it > 0L },
            dischargeStartIsExact = prefs.getBoolean(KEY_DISCHARGE_START_EXACT, false),
            plugged = if (prefs.contains(KEY_PLUGGED)) prefs.getBoolean(KEY_PLUGGED, false) else null,
            pluggedUpdatedAtMillis = prefs
                .getLong(KEY_PLUGGED_UPDATED_AT_MILLIS, -1L)
                .takeIf { it > 0L },
            pluggedFromBroadcast = prefs.getBoolean(KEY_PLUGGED_FROM_BROADCAST, false),
            lastStatus = rawStatus?.let { runCatching { BatteryStatus.valueOf(it) }.getOrNull() },
            lastStatusUpdatedAtMillis = prefs
                .getLong(KEY_LAST_STATUS_UPDATED_AT_MILLIS, -1L)
                .takeIf { it > 0L },
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val PREFERENCES_NAME = "battery_session_store"
    private const val KEY_DISCHARGING_STARTED_AT_MILLIS = "discharging_started_at_millis"
    private const val KEY_DISCHARGE_START_EXACT = "discharging_started_exact"
    private const val KEY_LAST_STATUS = "last_status"
    private const val KEY_LAST_STATUS_UPDATED_AT_MILLIS = "last_status_updated_at_millis"
    private const val KEY_PLUGGED = "plugged"
    private const val KEY_PLUGGED_UPDATED_AT_MILLIS = "plugged_updated_at_millis"
    private const val KEY_PLUGGED_FROM_BROADCAST = "plugged_from_broadcast"
}
