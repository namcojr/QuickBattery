package com.quickbattery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class BatteryInsight(
    val label: String,
    val value: String,
)
