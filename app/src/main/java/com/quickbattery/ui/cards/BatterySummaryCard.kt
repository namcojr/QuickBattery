package com.quickbattery.ui.cards

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quickbattery.ui.components.BatteryCard
import com.quickbattery.ui.components.MetricRow
import com.quickbattery.ui.format.UnavailableValue
import com.quickbattery.ui.format.formatDuration
import com.quickbattery.ui.format.formatPercent

@Composable
fun BatterySummaryCard(
    levelPercent: Int?,
    remainingRuntimeMillis: Long?,
    estimatedFullRuntimeMillis: Long?,
) {
    val remainingRuntimeLabel = remainingRuntimeMillis?.let(::formatDuration) ?: "Learning..."
    val fullRuntimeLabel = estimatedFullRuntimeMillis?.let(::formatDuration) ?: "Learning..."

    BatteryCard(title = "Battery Summary") {
        Text(
            text = remainingRuntimeLabel,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
        )

        Text(
            text = "Estimated remaining time",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
        )

        BatteryPillIndicator(levelPercent = levelPercent)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Column {
            //     Text(
            //         text = "Battery",
            //         style = MaterialTheme.typography.bodyMedium,
            //         color = MaterialTheme.colorScheme.onSurfaceVariant,
            //     )
            //     Text(
            //         text = formatPercent(levelPercent),
            //         style = MaterialTheme.typography.titleLarge,
            //         fontWeight = FontWeight.SemiBold,
            //     )
            // }

            Column {
                MetricRow(
                    label = "A full charge should last",
                    value = fullRuntimeLabel,
                    emphasized = true,
                )
            }
        }
    }
}

@Composable
private fun BatteryPillIndicator(levelPercent: Int?) {
    val rawLevel = (levelPercent ?: 0).coerceIn(0, 100)
    val target = rawLevel / 100f
    val animatedFraction by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 600),
        label = "battery-fill",
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(100.dp))
                .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFraction)
                    .height(28.dp)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(MaterialTheme.colorScheme.secondary),
            )
        }

        Text(
            text = if (levelPercent == null) UnavailableValue else "$rawLevel%",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
        )
    }
}
