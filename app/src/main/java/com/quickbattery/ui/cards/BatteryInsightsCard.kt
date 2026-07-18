package com.quickbattery.ui.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.quickbattery.domain.model.BatteryInsight
import com.quickbattery.ui.components.BatteryCard
import com.quickbattery.ui.components.MetricRow
import com.quickbattery.ui.format.UnavailableValue

private val hiddenInsightLabels = setOf("Battery Health", "Capacity", "Charge Cycles")

@Composable
fun BatteryInsightsCard(insights: List<BatteryInsight>) {
    val visibleInsights = insights.filterNot { insight ->
        hiddenInsightLabels.any { hidden -> hidden.equals(insight.label, ignoreCase = true) }
    }

    BatteryCard(
        title = "Battery Insights",
        //subtitle = "Metrics available on this device",
    ) {
        if (visibleInsights.isEmpty()) {
            Text(
                text = UnavailableValue,
                style = MaterialTheme.typography.bodyLarge,
            )
            return@BatteryCard
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            visibleInsights.forEachIndexed { index, insight ->
                MetricRow(
                    label = insight.label,
                    value = insight.value,
                )

                if (index < visibleInsights.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.22f),
                    )
                }
            }
        }
    }
}
