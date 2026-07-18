package com.quickbattery.ui.format

import java.util.Locale
import kotlin.math.roundToInt

const val UnavailableValue = "Unavailable"

fun formatPercent(percent: Int?): String {
    return percent?.let { "$it%" } ?: UnavailableValue
}

fun formatDuration(durationMillis: Long?): String {
    val millis = durationMillis ?: return UnavailableValue
    if (millis <= 0L) return "0m"

    val totalMinutes = millis / 60_000L
    val days = totalMinutes / (24L * 60L)
    val hours = (totalMinutes % (24L * 60L)) / 60L
    val minutes = totalMinutes % 60L

    return buildString {
        if (days > 0L) append("${days}d ")
        if (hours > 0L || days > 0L) append("${hours}h ")
        append("${minutes}m")
    }.trim()
}

fun formatRecordDuration(durationMillis: Long?): String? {
    val millis = durationMillis?.takeIf { it > 0L } ?: return null

    val totalMinutes = millis / 60_000L
    val days = totalMinutes / (24L * 60L)
    val hours = (totalMinutes % (24L * 60L)) / 60L
    val minutes = totalMinutes % 60L

    // Above 24h show a coarse "Xd Yh"; below, a compact "Hh Mm" (e.g. "23h16m").
    return if (days > 0L) {
        "${days}d ${hours}h"
    } else {
        "${hours}h${minutes}m"
    }
}

fun formatContribution(value: Float?): String {
    return value?.let {
        String.format(Locale.getDefault(), "%.1f%%", it.coerceAtLeast(0f))
    } ?: UnavailableValue
}

fun formatCurrentMilliAmps(microAmps: Int?): String {
    val value = microAmps ?: return UnavailableValue
    return "${(value / 1000f).roundToInt()} mA"
}
