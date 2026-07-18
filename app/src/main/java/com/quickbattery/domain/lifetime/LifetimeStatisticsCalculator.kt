package com.quickbattery.domain.lifetime

import com.quickbattery.domain.model.BatteryHealthUi
import com.quickbattery.domain.model.ChargingHabitsUi
import com.quickbattery.domain.model.LifetimeProjectionUi
import com.quickbattery.domain.model.LifetimeStatistics
import com.quickbattery.domain.model.PhoneAgeUi
import com.quickbattery.domain.model.ProjectionEntryUi
import com.quickbattery.domain.model.TimelineEntryUi
import com.quickbattery.domain.model.UsageProfileUi
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Input required to compute [LifetimeStatistics].
 *
 * Everything is derived from these four values only, in line with the feature's
 * "no history, no background work" constraint.
 */
data class LifetimeInput(
    val purchaseDateMillis: Long,
    val nowMillis: Long,
    val chargeCycles: Int?,
    val batteryHealth: String?,
    val zoneId: ZoneId = ZoneId.systemDefault(),
)

/**
 * Pure calculator for all Battery Lifetime statistics.
 *
 * This class owns every calculation for the feature. It is deterministic (aside from
 * the injected clock/zone) and has no Android or coroutine dependencies so that each
 * calculation can be unit-tested independently. Presentation-only concerns such as
 * shuffling facts are intentionally left to the UI layer.
 */
@Singleton
class LifetimeStatisticsCalculator @Inject constructor() {

    fun calculate(input: LifetimeInput): LifetimeStatistics {
        val purchaseDate = input.purchaseDateMillis.toLocalDate(input.zoneId)
        val currentDate = input.nowMillis.toLocalDate(input.zoneId)

        val totalDays = ChronoUnit.DAYS.between(purchaseDate, currentDate).coerceAtLeast(0L)
        // Fractional age gives smoother rates on very young phones without dividing by zero.
        val fractionalDays = ((input.nowMillis - input.purchaseDateMillis).toDouble() / DAY_MILLIS)
            .coerceAtLeast(0.0)

        val cyclesPerDay = computeCyclesPerDay(input.chargeCycles, fractionalDays)

        return LifetimeStatistics(
            phoneAge = buildPhoneAge(purchaseDate, currentDate, totalDays),
            chargingHabits = buildChargingHabits(cyclesPerDay),
            projection = buildProjection(input, cyclesPerDay),
            health = buildHealth(input, totalDays, cyclesPerDay),
            usageProfile = buildUsageProfile(cyclesPerDay),
            facts = buildFacts(input, totalDays, cyclesPerDay),
            timeline = buildTimeline(input, purchaseDate, currentDate, totalDays, cyclesPerDay),
        )
    }

    private fun computeCyclesPerDay(chargeCycles: Int?, fractionalDays: Double): Double? {
        val cycles = chargeCycles ?: return null
        if (cycles < 0 || fractionalDays <= 0.0) {
            return null
        }
        return cycles.toDouble() / fractionalDays
    }

    private fun buildPhoneAge(
        purchaseDate: LocalDate,
        currentDate: LocalDate,
        totalDays: Long,
    ): PhoneAgeUi {
        return PhoneAgeUi(
            ageDisplay = formatAge(purchaseDate, currentDate, totalDays),
            purchaseDateLabel = purchaseDate.format(FULL_DATE_FORMATTER),
            currentDateLabel = currentDate.format(FULL_DATE_FORMATTER),
        )
    }

    private fun formatAge(purchaseDate: LocalDate, currentDate: LocalDate, totalDays: Long): String {
        if (totalDays < DAYS_IN_MONTH_THRESHOLD) {
            return if (totalDays == 1L) "1 day" else "$totalDays days"
        }
        val period = Period.between(purchaseDate, currentDate)
        val parts = buildList {
            if (period.years > 0) add(pluralize(period.years, "year"))
            if (period.months > 0) add(pluralize(period.months, "month"))
            if (period.days > 0) add(pluralize(period.days, "day"))
        }
        return if (parts.isEmpty()) "$totalDays days" else parts.joinToString(" ")
    }

    private fun buildChargingHabits(cyclesPerDay: Double?): ChargingHabitsUi {
        if (cyclesPerDay == null) {
            return ChargingHabitsUi(
                available = false,
                perDay = UNAVAILABLE,
                perMonth = UNAVAILABLE,
                perYear = UNAVAILABLE,
                averageDaysBetweenCycles = UNAVAILABLE,
            )
        }
        val perMonth = cyclesPerDay * DAYS_IN_AVERAGE_MONTH
        val perYear = cyclesPerDay * DAYS_IN_YEAR
        val daysBetween = if (cyclesPerDay > 0.0) 1.0 / cyclesPerDay else null

        return ChargingHabitsUi(
            available = true,
            perDay = String.format(Locale.US, "%.2f/day", cyclesPerDay),
            perMonth = String.format(Locale.US, "%.1f/month", perMonth),
            perYear = "${perYear.roundToInt()}/year",
            averageDaysBetweenCycles = daysBetween
                ?.let { String.format(Locale.US, "%.1f days", it) }
                ?: UNAVAILABLE,
        )
    }

    private fun buildProjection(input: LifetimeInput, cyclesPerDay: Double?): LifetimeProjectionUi {
        val cycles = input.chargeCycles
        if (cycles == null || cyclesPerDay == null || cyclesPerDay <= 0.0) {
            return LifetimeProjectionUi(available = false, entries = emptyList())
        }

        val entries = PROJECTION_TARGETS.map { target ->
            val cycleLabel = "${target}th Cycle"
            val remainingCycles = target - cycles
            if (remainingCycles <= 0) {
                ProjectionEntryUi(
                    cycleLabel = cycleLabel,
                    expectedDate = "Already reached",
                    remaining = "Milestone passed",
                )
            } else {
                val daysToReach = remainingCycles / cyclesPerDay
                val reachDate = Instant.ofEpochMilli(input.nowMillis)
                    .plusMillis((daysToReach * DAY_MILLIS).roundToLong())
                    .toLocalDate(input.zoneId)
                val yearsRemaining = daysToReach / DAYS_IN_YEAR
                ProjectionEntryUi(
                    cycleLabel = cycleLabel,
                    expectedDate = reachDate.format(MONTH_YEAR_FORMATTER),
                    remaining = String.format(Locale.US, "%.1f years remaining", yearsRemaining),
                )
            }
        }
        return LifetimeProjectionUi(available = true, entries = entries)
    }

    private fun buildHealth(
        input: LifetimeInput,
        totalDays: Long,
        cyclesPerDay: Double?,
    ): BatteryHealthUi {
        val health = input.batteryHealth
        if (health.isNullOrBlank()) {
            return BatteryHealthUi(
                available = false,
                health = UNAVAILABLE,
                chargeCycles = input.chargeCycles?.toString() ?: UNAVAILABLE,
                phoneAge = "$totalDays days",
                estimatedUsageRate = cyclesPerDay
                    ?.let { String.format(Locale.US, "%.2f cycles/day", it) }
                    ?: UNAVAILABLE,
                interpretation = UNAVAILABLE,
            )
        }
        return BatteryHealthUi(
            available = true,
            health = health,
            chargeCycles = input.chargeCycles?.toString() ?: UNAVAILABLE,
            phoneAge = "$totalDays days",
            estimatedUsageRate = cyclesPerDay
                ?.let { String.format(Locale.US, "%.2f cycles/day", it) }
                ?: UNAVAILABLE,
            interpretation = interpretHealth(cyclesPerDay, health),
        )
    }

    /**
     * Qualitative interpretation based primarily on cycles/day, downgraded one tier when the
     * reported battery health is anything other than a healthy state. Never produces a
     * degradation percentage or a future health prediction.
     */
    private fun interpretHealth(cyclesPerDay: Double?, health: String): String {
        if (cyclesPerDay == null) {
            return UNAVAILABLE
        }
        val baseTier = when {
            cyclesPerDay < 0.25 -> 0
            cyclesPerDay < 0.50 -> 1
            cyclesPerDay < 0.90 -> 2
            cyclesPerDay < 1.30 -> 3
            else -> 4
        }
        val healthy = health.equals("Good", ignoreCase = true) ||
            health.equals("Unknown", ignoreCase = true)
        val tier = if (healthy) baseTier else (baseTier + 1).coerceAtMost(HEALTH_TIERS.lastIndex)
        return HEALTH_TIERS[tier]
    }

    private fun buildUsageProfile(cyclesPerDay: Double?): UsageProfileUi {
        if (cyclesPerDay == null) {
            return UsageProfileUi(
                available = false,
                classification = UNAVAILABLE,
                explanation = "Charge cycle data is not available on this device.",
            )
        }
        val (classification, explanation) = when {
            cyclesPerDay < 0.25 -> "Very Light User" to
                "You charge your phone far less frequently than most users."
            cyclesPerDay < 0.50 -> "Light User" to
                "You charge your phone less frequently than most users."
            cyclesPerDay < 0.90 -> "Average User" to
                "Your charging habits are typical for most phone owners."
            cyclesPerDay < 1.30 -> "Heavy User" to
                "You charge your phone more frequently than most users."
            else -> "Power User" to
                "You charge your phone far more frequently than most users."
        }
        return UsageProfileUi(available = true, classification = classification, explanation = explanation)
    }

    /**
     * Builds the complete list of valid, data-derived facts. Rotation/shuffling of these facts on
     * every screen load is a presentation concern handled by the UI layer, keeping this calculation
     * deterministic and testable.
     */
    private fun buildFacts(
        input: LifetimeInput,
        totalDays: Long,
        cyclesPerDay: Double?,
    ): List<String> {
        val facts = mutableListOf<String>()
        val cycles = input.chargeCycles

        if (cyclesPerDay != null) {
            val perMonth = cyclesPerDay * DAYS_IN_AVERAGE_MONTH
            facts += String.format(Locale.US, "You have averaged %.1f charge cycles per month.", perMonth)
            facts += String.format(Locale.US, "You average %.2f charge cycles per day.", cyclesPerDay)
            if (cyclesPerDay > 0.0) {
                val daysBetween = 1.0 / cyclesPerDay
                facts += String.format(
                    Locale.US,
                    "Your phone averages one full charge every %.1f days.",
                    daysBetween,
                )
            }
        }

        if (cycles != null) {
            facts += if (cycles < 100) {
                "Your battery has completed only $cycles full charge cycles."
            } else {
                "Your battery has completed $cycles full charge cycles."
            }
            if (cycles < EARLY_LIFE_CYCLE_THRESHOLD) {
                facts += "Your battery is still in the early part of its expected service life."
            }
        }

        if (cyclesPerDay != null && cyclesPerDay > 0.0 && cycles != null) {
            val remaining = FIRST_MILESTONE - cycles
            if (remaining > 0) {
                val years = (remaining / cyclesPerDay) / DAYS_IN_YEAR
                facts += String.format(
                    Locale.US,
                    "At your current usage, reaching %d charge cycles will take approximately %.1f more years.",
                    FIRST_MILESTONE,
                    years,
                )
            }
        }

        facts += if (totalDays == 1L) {
            "Your phone is 1 day old."
        } else {
            "Your phone is $totalDays days old."
        }

        return facts
    }

    private fun buildTimeline(
        input: LifetimeInput,
        purchaseDate: LocalDate,
        currentDate: LocalDate,
        totalDays: Long,
        cyclesPerDay: Double?,
    ): List<TimelineEntryUi> {
        val timeline = mutableListOf<TimelineEntryUi>()

        timeline += TimelineEntryUi("Purchase Date", purchaseDate.format(FULL_DATE_FORMATTER))
        timeline += TimelineEntryUi(
            title = "Current Date",
            subtitle = "${currentDate.format(FULL_DATE_FORMATTER)} · $totalDays days owned",
        )
        timeline += TimelineEntryUi(
            title = "Current Charge Cycle",
            subtitle = input.chargeCycles?.let { "$it cycles completed" } ?: UNAVAILABLE,
        )

        PROJECTION_TARGETS.forEach { target ->
            val subtitle = projectionSubtitle(input, target, cyclesPerDay)
            timeline += TimelineEntryUi(title = "Projected ${target}th Cycle", subtitle = subtitle)
        }

        return timeline
    }

    private fun projectionSubtitle(input: LifetimeInput, target: Int, cyclesPerDay: Double?): String {
        val cycles = input.chargeCycles
        if (cycles == null || cyclesPerDay == null || cyclesPerDay <= 0.0) {
            return UNAVAILABLE
        }
        val remainingCycles = target - cycles
        if (remainingCycles <= 0) {
            return "Already reached"
        }
        val daysToReach = remainingCycles / cyclesPerDay
        val reachDate = Instant.ofEpochMilli(input.nowMillis)
            .plusMillis((daysToReach * DAY_MILLIS).roundToLong())
            .toLocalDate(input.zoneId)
        val years = daysToReach / DAYS_IN_YEAR
        return String.format(Locale.US, "%s · %.1f years away", reachDate.format(MONTH_YEAR_FORMATTER), years)
    }

    private fun pluralize(value: Int, unit: String): String {
        return if (value == 1) "1 $unit" else "$value ${unit}s"
    }

    private fun Long.toLocalDate(zoneId: ZoneId): LocalDate =
        Instant.ofEpochMilli(this).atZone(zoneId).toLocalDate()

    private fun Instant.toLocalDate(zoneId: ZoneId): LocalDate =
        atZone(zoneId).toLocalDate()

    private companion object {
        const val UNAVAILABLE = "Unavailable"
        const val DAY_MILLIS = 24.0 * 60.0 * 60.0 * 1000.0
        const val DAYS_IN_AVERAGE_MONTH = 30.44
        const val DAYS_IN_YEAR = 365.25
        const val DAYS_IN_MONTH_THRESHOLD = 30L
        const val EARLY_LIFE_CYCLE_THRESHOLD = 200
        const val FIRST_MILESTONE = 500

        val PROJECTION_TARGETS = listOf(500, 800, 1000)
        val HEALTH_TIERS = listOf(
            "Excellent",
            "Very Good",
            "Normal",
            "Heavy Usage",
            "Very Heavy Usage",
        )

        val FULL_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)
        val MONTH_YEAR_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)
    }
}
