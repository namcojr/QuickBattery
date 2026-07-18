package com.quickbattery.ui.cards

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.quickbattery.ui.components.BatteryCard
import com.quickbattery.ui.components.MetricRow
import com.quickbattery.ui.format.UnavailableValue
import com.quickbattery.ui.format.formatDuration
import com.quickbattery.ui.format.formatRecordDuration

@Composable
fun SinceLastChargeCard(
    sinceLastChargeMillis: Long?,
    recordSinceLastChargeMillis: Long?,
    batteryHealth: String?,
    chargeCycles: Int?,
) {
    val sinceLastChargeLabel = sinceLastChargeMillis?.let(::formatDuration) ?: "Learning..."
    val recordLabel = formatRecordDuration(recordSinceLastChargeMillis)

    BatteryCard(
        title = "Since Last Charge",
        // subtitle = "Public Android metrics only",
    ) {
        MetricRow(
            label = "Time Since Charging Stopped",
            value = sinceLastChargeLabel,
            emphasized = true,
        )

        if (recordLabel != null) {
            Text(
                text = "Record Time $recordLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
            )
        }

        MetricRow(
            label = "Battery Health",
            value = batteryHealth ?: UnavailableValue,
        )

        MetricRow(
            label = "Charge Cycles",
            value = chargeCycles?.toString() ?: UnavailableValue,
        )

        if (sinceLastChargeMillis == null || batteryHealth == null) {
            Text(
                text = "Learning requires ~20m and 1% drop while unplugged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
            )
        }
    }
}
