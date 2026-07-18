package com.quickbattery.ui.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.quickbattery.domain.model.AppBatteryUsage
import com.quickbattery.ui.components.BatteryCard
import com.quickbattery.ui.format.formatContribution
import com.quickbattery.ui.format.formatDuration

@Composable
fun AppUsageCard(
    usagePermissionGranted: Boolean,
    appUsage: List<AppBatteryUsage>,
    canExpandApps: Boolean,
    showAllApps: Boolean,
    onToggleShowAllApps: () -> Unit,
    onOpenUsageAccessSettings: () -> Unit,
) {
    BatteryCard(
        title = "Application Battery Usage",
        // subtitle = "Estimated contribution from app screen-on time",
    ) {
        if (!usagePermissionGranted) {
            Text(
                text = "Usage Access is required to read app usage statistics.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
            )

            Button(onClick = onOpenUsageAccessSettings) {
                Text("Grant Usage Access")
            }
            return@BatteryCard
        }

        if (appUsage.isEmpty()) {
            Text(
                text = "No usage data available yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
            )
            return@BatteryCard
        }

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            appUsage.forEach { usage ->
                AppUsageRow(usage = usage)
            }
        }

        AnimatedVisibility(visible = canExpandApps) {
            Button(onClick = onToggleShowAllApps) {
                Text(text = if (showAllApps) "Show Top 10" else "Show All")
            }
        }
    }
}

@Composable
private fun AppUsageRow(usage: AppBatteryUsage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(iconPng = usage.iconPng, appName = usage.appName)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = usage.appName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text = "Screen-on: ${formatDuration(usage.screenOnTimeMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
            )
        }

        Text(
            text = formatContribution(usage.estimatedBatteryContributionPercent),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AppIcon(
    iconPng: ByteArray?,
    appName: String,
) {
    if (iconPng != null) {
        AsyncImage(
            model = iconPng,
            contentDescription = "$appName icon",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)),
        )
        return
    }

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Android,
            contentDescription = "$appName icon",
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
