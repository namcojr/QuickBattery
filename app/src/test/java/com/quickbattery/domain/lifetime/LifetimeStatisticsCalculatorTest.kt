package com.quickbattery.domain.lifetime

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifetimeStatisticsCalculatorTest {

    private val calculator = LifetimeStatisticsCalculator()
    private val utc = ZoneId.of("UTC")

    // 2024-01-01T00:00:00Z
    private val purchaseMillis = 1_704_067_200_000L
    private val dayMillis = 86_400_000L

    private fun input(
        days: Long,
        chargeCycles: Int?,
        health: String? = "Good",
    ) = LifetimeInput(
        purchaseDateMillis = purchaseMillis,
        nowMillis = purchaseMillis + days * dayMillis,
        chargeCycles = chargeCycles,
        batteryHealth = health,
        zoneId = utc,
    )

    @Test
    fun phoneAge_formatsAsMonthsAndDays() {
        val stats = calculator.calculate(input(days = 184, chargeCycles = 184))
        assertEquals("6 months 2 days", stats.phoneAge.ageDisplay)
        assertEquals("January 1, 2024", stats.phoneAge.purchaseDateLabel)
        assertEquals("July 3, 2024", stats.phoneAge.currentDateLabel)
    }

    @Test
    fun phoneAge_belowOneMonth_formatsAsDays() {
        val stats = calculator.calculate(input(days = 12, chargeCycles = 3))
        assertEquals("12 days", stats.phoneAge.ageDisplay)
    }

    @Test
    fun chargingHabits_computeRatesFromCyclesAndAge() {
        val stats = calculator.calculate(input(days = 184, chargeCycles = 184))
        val habits = stats.chargingHabits
        assertTrue(habits.available)
        assertEquals("1.00/day", habits.perDay)
        assertEquals("30.4/month", habits.perMonth)
        assertEquals("365/year", habits.perYear)
        assertEquals("1.0 days", habits.averageDaysBetweenCycles)
    }

    @Test
    fun chargingHabits_unavailableWithoutCycles() {
        val stats = calculator.calculate(input(days = 184, chargeCycles = null))
        assertFalse(stats.chargingHabits.available)
        assertEquals("Unavailable", stats.chargingHabits.perDay)
    }

    @Test
    fun projection_computesMilestonesFromRate() {
        val stats = calculator.calculate(input(days = 184, chargeCycles = 184))
        val projection = stats.projection
        assertTrue(projection.available)
        assertEquals(3, projection.entries.size)
        assertEquals("500th Cycle", projection.entries[0].cycleLabel)
        assertEquals("0.9 years remaining", projection.entries[0].remaining)
    }

    @Test
    fun projection_markesAlreadyReachedMilestones() {
        val stats = calculator.calculate(input(days = 600, chargeCycles = 600))
        assertEquals("Already reached", stats.projection.entries[0].expectedDate)
    }

    @Test
    fun usageProfile_classifiesHeavyUser() {
        val stats = calculator.calculate(input(days = 184, chargeCycles = 184))
        assertEquals("Heavy User", stats.usageProfile.classification)
    }

    @Test
    fun usageProfile_classifiesVeryLightUser() {
        // 10 cycles over 184 days -> ~0.05/day
        val stats = calculator.calculate(input(days = 184, chargeCycles = 10))
        assertEquals("Very Light User", stats.usageProfile.classification)
    }

    @Test
    fun healthInterpretation_goodHealthUsesBaseTier() {
        val stats = calculator.calculate(input(days = 184, chargeCycles = 184, health = "Good"))
        assertTrue(stats.health.available)
        assertEquals("Heavy Usage", stats.health.interpretation)
    }

    @Test
    fun healthInterpretation_poorHealthDowngradesTier() {
        val stats = calculator.calculate(input(days = 184, chargeCycles = 184, health = "Overheat"))
        assertEquals("Very Heavy Usage", stats.health.interpretation)
    }

    @Test
    fun health_unavailableWhenHealthMissing() {
        val stats = calculator.calculate(input(days = 184, chargeCycles = 184, health = null))
        assertFalse(stats.health.available)
        assertEquals("Unavailable", stats.health.health)
    }

    @Test
    fun facts_areDerivedFromData() {
        val stats = calculator.calculate(input(days = 184, chargeCycles = 184))
        assertTrue(stats.facts.any { it.contains("184 full charge cycles") })
        assertTrue(stats.facts.any { it.contains("early part of its expected service life") })
    }

    @Test
    fun timeline_containsAllMilestones() {
        val stats = calculator.calculate(input(days = 184, chargeCycles = 184))
        val titles = stats.timeline.map { it.title }
        assertEquals(
            listOf(
                "Purchase Date",
                "Current Date",
                "Current Charge Cycle",
                "Projected 500th Cycle",
                "Projected 800th Cycle",
                "Projected 1000th Cycle",
            ),
            titles,
        )
    }
}
