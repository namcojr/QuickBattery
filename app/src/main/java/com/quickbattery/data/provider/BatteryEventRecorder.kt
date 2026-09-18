package com.quickbattery.data.provider

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.quickbattery.domain.model.BatteryStatus

/**
 * Single source of truth for turning raw battery broadcasts into persisted state.
 *
 * Three independent paths feed this object, deliberately overlapping so that losing one still
 * leaves the session boundary correct:
 *
 *  1. [PowerConnectionReceiver] - the manifest receiver, the only path that works with no process
 *     running, but silently dead while the OEM keeps the app force-stopped or frozen.
 *  2. [BatteryMonitorService] - a runtime-registered receiver inside a foreground service, which
 *     also sees ACTION_BATTERY_CHANGED (never delivered to manifest receivers) and therefore
 *     notices a plug change even when the dedicated power broadcast is dropped.
 *  3. [reconcile] - a repair pass run on app open, boot and service start that compares the live
 *     plugged state against what was last persisted and reconstructs any boundary that was missed
 *     while the process was frozen.
 *
 * Boundaries are keyed on BatteryManager.EXTRA_PLUGGED rather than BATTERY_STATUS_*, because a
 * plugged-in phone frequently reports FULL or NOT_CHARGING and those must not open a discharge
 * session.
 */
internal object BatteryEventRecorder {

    /** Charger connected: close the discharge session and commit its length to the records. */
    fun onPowerConnected(
        context: Context,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        val batteryIntent = readBatteryIntent(context)
        val start = BatterySessionStore.endDischargeSession(context, timestampMillis, exact = true)
        finalizeSession(context, start, timestampMillis, batteryIntent)
        recordBoundarySample(context, timestampMillis, BatteryStatus.Charging, batteryIntent)
    }

    /** Charger disconnected: this instant is the start of the new discharge session. */
    fun onPowerDisconnected(
        context: Context,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        val start = BatterySessionStore.beginDischargeSession(context, timestampMillis, exact = true)
        recordBoundarySample(context, start, BatteryStatus.Discharging, readBatteryIntent(context))
    }

    /**
     * Handles a streamed ACTION_BATTERY_CHANGED. Only the monitor service receives these.
     *
     * Besides keeping the level history fresh, this is the redundant plug detector: if the plugged
     * flag flipped since the last observation, the corresponding power broadcast never arrived (or
     * arrived while the app was frozen) and the boundary is applied from here instead.
     */
    fun onBatteryChanged(
        context: Context,
        batteryIntent: Intent?,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        val plugged = readPlugged(batteryIntent) ?: return
        val status = effectiveStatus(readStatus(batteryIntent) ?: BatteryStatus.Unknown, plugged)
        val previous = BatterySessionStore.read(context)

        when {
            previous.plugged == true && !plugged ->
                BatterySessionStore.beginDischargeSession(context, timestampMillis, exact = true)

            previous.plugged == false && plugged -> {
                val start = BatterySessionStore.endDischargeSession(context, timestampMillis, exact = true)
                finalizeSession(context, start, timestampMillis, batteryIntent)
            }

            else -> BatterySessionStore.recordObservation(
                context = context,
                status = status,
                plugged = plugged,
                timestampMillis = timestampMillis,
                fromBroadcast = true,
            )
        }

        val level = readLevelPercent(batteryIntent) ?: return
        BatteryLevelHistoryStore.appendSampleIfChanged(
            context = context,
            timestampMillis = timestampMillis,
            levelPercent = level,
            status = status,
        )
    }

    /**
     * Repairs session state after the process was not running to see the events.
     *
     * This is what makes a dropped broadcast survivable. The live sticky battery intent always
     * tells the truth about the charger right now; comparing it with the last persisted plugged
     * state reveals a boundary that was missed, and the level history is used to estimate when it
     * happened. Estimates are deliberately conservative - a reconstructed session is never allowed
     * to look longer than the evidence supports - so a missed event can shorten a record but never
     * inflate one.
     *
     * Safe and cheap to call often; when nothing changed it just refreshes the observation.
     */
    fun reconcile(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val batteryIntent = readBatteryIntent(context)
        val plugged = readPlugged(batteryIntent) ?: return
        val status = effectiveStatus(readStatus(batteryIntent) ?: BatteryStatus.Unknown, plugged)
        val previous = BatterySessionStore.read(context)

        // The sticky ACTION_BATTERY_CHANGED lags the power broadcasts by a moment, so straight
        // after a real event it can still describe the previous state. Repairing from a reading
        // that contradicts a broadcast this recent would undo the exact boundary just recorded.
        val contradictsRecentBroadcast = previous.pluggedFromBroadcast &&
            previous.plugged != null &&
            previous.plugged != plugged &&
            previous.pluggedUpdatedAtMillis?.let { nowMillis - it in 0L..STICKY_SETTLE_GRACE_MILLIS } == true
        if (contradictsRecentBroadcast) {
            return
        }

        when {
            // Missed a disconnect while frozen: reopen the session at the last moment we have
            // evidence the charger was still attached.
            previous.plugged == true && !plugged ->
                BatterySessionStore.beginDischargeSession(
                    context = context,
                    timestampMillis = estimateUnplugMillis(context, previous, nowMillis),
                    exact = false,
                )

            // Missed a connect while frozen: close the session at the last moment we have evidence
            // the device was still running on battery, and bank the result.
            previous.plugged == false && plugged -> {
                val end = estimateReconnectMillis(context, previous, nowMillis)
                val start = BatterySessionStore.endDischargeSession(context, end, exact = false)
                finalizeSession(context, start, end, batteryIntent)
            }

            // On battery with no session on record: first launch, a reset, or a boundary lost
            // before this repair pass existed. Anchor it to the newest charging evidence.
            !plugged && previous.dischargeStartedAtMillis == null ->
                BatterySessionStore.beginDischargeSession(
                    context = context,
                    timestampMillis = estimateUnplugMillis(context, previous, nowMillis),
                    exact = false,
                )

            else -> BatterySessionStore.recordObservation(
                context = context,
                status = status,
                plugged = plugged,
                timestampMillis = nowMillis,
                fromBroadcast = false,
            )
        }

        val level = readLevelPercent(batteryIntent) ?: return
        BatteryLevelHistoryStore.appendSampleIfChanged(
            context = context,
            timestampMillis = nowMillis,
            levelPercent = level,
            status = status,
        )
    }

    /**
     * Commits a completed discharge session to the persistent records.
     *
     * Before this existed the session length was simply deleted on reconnect and the record could
     * only ever be set by the UI happening to be open at the right moment, so a long session that
     * ended with the app closed was never registered at all.
     */
    private fun finalizeSession(
        context: Context,
        startMillis: Long?,
        endMillis: Long,
        batteryIntent: Intent?,
    ) {
        if (startMillis == null) {
            return
        }

        val durationMillis = endMillis - startMillis
        if (durationMillis !in MIN_RECORDABLE_SESSION_MILLIS..MAX_PLAUSIBLE_SESSION_MILLIS) {
            return
        }

        BatteryRecordStore.updateAndGetLongestSinceLastCharge(
            context = context,
            candidateMillis = durationMillis,
            commitImmediately = true,
        )

        // The session also yields a real 100 -> 0% projection, which is otherwise only ever
        // captured while the dashboard is open. Requires a meaningful drop so a short top-up
        // cycle can't extrapolate a wild figure.
        val endLevel = readLevelPercent(batteryIntent) ?: return
        val startLevel = levelAtOrAfter(context, startMillis) ?: return
        val consumedPercent = startLevel - endLevel
        if (consumedPercent < MIN_CONSUMED_PERCENT_FOR_PROJECTION) {
            return
        }

        BatteryRecordStore.updateAndGetFullRuntimeEstimate(
            context = context,
            candidateMillis = durationMillis * 100L / consumedPercent,
            nowMillis = endMillis,
            commitImmediately = true,
        )
    }

    /**
     * Latest instant we can show the charger was still connected, used as the start of a session
     * whose disconnect broadcast was missed.
     */
    private fun estimateUnplugMillis(
        context: Context,
        previous: BatterySessionStore.SessionState,
        nowMillis: Long,
    ): Long {
        val fromHistory = BatteryLevelHistoryStore
            .getRecentSamples(context, EVIDENCE_LOOKBACK_WINDOW_MILLIS)
            .lastOrNull { it.status == BatteryStatus.Charging || it.status == BatteryStatus.Full }
            ?.timestampMillis
        val fromState = previous.pluggedUpdatedAtMillis?.takeIf { previous.plugged == true }

        return maxOf(fromHistory ?: 0L, fromState ?: 0L)
            .takeIf { it > 0L }
            ?.coerceAtMost(nowMillis)
            ?: nowMillis
    }

    /**
     * Latest instant we can show the device was still on battery, used as the end of a session
     * whose connect broadcast was missed. Erring late here would overstate the session, so the
     * newest discharge evidence - not "now" - is the answer.
     */
    private fun estimateReconnectMillis(
        context: Context,
        previous: BatterySessionStore.SessionState,
        nowMillis: Long,
    ): Long {
        val fromHistory = BatteryLevelHistoryStore
            .getRecentSamples(context, EVIDENCE_LOOKBACK_WINDOW_MILLIS)
            .lastOrNull { it.status == BatteryStatus.Discharging || it.status == BatteryStatus.NotCharging }
            ?.timestampMillis
        val fromState = previous.lastStatusUpdatedAtMillis?.takeIf { previous.plugged == false }

        val estimate = maxOf(fromHistory ?: 0L, fromState ?: 0L).takeIf { it > 0L } ?: nowMillis
        val floor = previous.dischargeStartedAtMillis ?: 0L
        return estimate.coerceIn(floor, nowMillis)
    }

    /** Battery level recorded at, or as soon as possible after, [timestampMillis]. */
    private fun levelAtOrAfter(
        context: Context,
        timestampMillis: Long,
    ): Int? {
        return BatteryLevelHistoryStore
            .getRecentSamples(context, EVIDENCE_LOOKBACK_WINDOW_MILLIS)
            .firstOrNull { it.timestampMillis >= timestampMillis }
            ?.levelPercent
    }

    /**
     * Reconciles a reported status with the charger actually being attached.
     *
     * A phone keeps reporting FULL for several seconds after being unplugged at a high level, and
     * reports NOT_CHARGING while plugged in but charge-limited. Storing either verbatim poisons the
     * history: a FULL sample written just after an unplug looks like charging evidence newer than
     * the session start, which then discards the very unplug timestamp that was just recorded.
     * EXTRA_PLUGGED is the hardware truth, so it decides.
     */
    fun effectiveStatus(
        status: BatteryStatus,
        plugged: Boolean,
    ): BatteryStatus = when {
        !plugged && (status == BatteryStatus.Charging || status == BatteryStatus.Full) ->
            BatteryStatus.Discharging

        plugged && status == BatteryStatus.Discharging -> BatteryStatus.NotCharging

        else -> status
    }

    private fun recordBoundarySample(
        context: Context,
        timestampMillis: Long,
        status: BatteryStatus,
        batteryIntent: Intent?,
    ) {
        val levelPercent = readLevelPercent(batteryIntent) ?: return
        BatteryLevelHistoryStore.appendSample(
            context = context,
            timestampMillis = timestampMillis,
            levelPercent = levelPercent,
            status = status,
        )
    }

    fun readBatteryIntent(context: Context): Intent? {
        return runCatching {
            context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
    }

    fun readLevelPercent(batteryIntent: Intent?): Int? {
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) {
            return null
        }
        return (level * 100f / scale.toFloat()).toInt().coerceIn(0, 100)
    }

    /** True when a charger is physically attached, whatever the charging status reports. */
    fun readPlugged(batteryIntent: Intent?): Boolean? {
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return if (plugged < 0) null else plugged != 0
    }

    fun readStatus(batteryIntent: Intent?): BatteryStatus? {
        val code = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return when (code) {
            BatteryManager.BATTERY_STATUS_CHARGING -> BatteryStatus.Charging
            BatteryManager.BATTERY_STATUS_DISCHARGING -> BatteryStatus.Discharging
            BatteryManager.BATTERY_STATUS_FULL -> BatteryStatus.Full
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> BatteryStatus.NotCharging
            else -> null
        }
    }

    private const val STICKY_SETTLE_GRACE_MILLIS = 60L * 1000L
    private const val MIN_RECORDABLE_SESSION_MILLIS = 60L * 1000L
    private const val MAX_PLAUSIBLE_SESSION_MILLIS = 30L * 24L * 60L * 60L * 1000L
    private const val MIN_CONSUMED_PERCENT_FOR_PROJECTION = 10
    private const val EVIDENCE_LOOKBACK_WINDOW_MILLIS = 30L * 24L * 60L * 60L * 1000L
}
