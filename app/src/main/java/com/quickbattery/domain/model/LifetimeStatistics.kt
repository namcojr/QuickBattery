package com.quickbattery.domain.model

import androidx.compose.runtime.Immutable

/**
 * Immutable, fully pre-formatted representation of the Battery Lifetime screen.
 *
 * Every field is already formatted for display so that the UI layer only renders
 * strings and never performs calculations. Metrics that cannot be derived from the
 * available data are represented with an "Unavailable" label instead of being hidden.
 */
@Immutable
data class LifetimeStatistics(
    val phoneAge: PhoneAgeUi,
    val chargingHabits: ChargingHabitsUi,
    val projection: LifetimeProjectionUi,
    val health: BatteryHealthUi,
    val usageProfile: UsageProfileUi,
    val facts: List<String>,
    val timeline: List<TimelineEntryUi>,
)

@Immutable
data class PhoneAgeUi(
    val ageDisplay: String,
    val purchaseDateLabel: String,
    val currentDateLabel: String,
)

@Immutable
data class ChargingHabitsUi(
    val available: Boolean,
    val perDay: String,
    val perMonth: String,
    val perYear: String,
    val averageDaysBetweenCycles: String,
)

@Immutable
data class LifetimeProjectionUi(
    val available: Boolean,
    val entries: List<ProjectionEntryUi>,
)

@Immutable
data class ProjectionEntryUi(
    val cycleLabel: String,
    val expectedDate: String,
    val remaining: String,
)

@Immutable
data class BatteryHealthUi(
    val available: Boolean,
    val health: String,
    val chargeCycles: String,
    val phoneAge: String,
    val estimatedUsageRate: String,
    val interpretation: String,
)

@Immutable
data class UsageProfileUi(
    val available: Boolean,
    val classification: String,
    val explanation: String,
)

@Immutable
data class TimelineEntryUi(
    val title: String,
    val subtitle: String,
)
