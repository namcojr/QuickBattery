package com.quickbattery.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quickbattery.ui.cards.AppUsageCard
import com.quickbattery.ui.cards.BatteryInsightsCard
import com.quickbattery.ui.cards.BatterySummaryCard
import com.quickbattery.ui.cards.SinceLastChargeCard
import com.quickbattery.ui.components.BatteryCard
import com.quickbattery.ui.components.ShimmerPlaceholder
import com.quickbattery.ui.state.BatteryUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryDashboardScreen(
    uiState: BatteryUiState,
    onRefresh: () -> Unit,
    onToggleShowAllApps: () -> Unit,
    onOpenUsageAccessSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "QuickBattery",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        AnimatedContent(
            targetState = uiState.isLoading,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            },
            modifier = Modifier.fillMaxSize(),
            label = "dashboard-loading",
        ) { loading ->
            if (loading) {
                BatteryDashboardSkeleton(paddingValues = innerPadding)
            } else {
                BatteryDashboardContent(
                    uiState = uiState,
                    paddingValues = innerPadding,
                    onToggleShowAllApps = onToggleShowAllApps,
                    onOpenUsageAccessSettings = onOpenUsageAccessSettings,
                )
            }
        }
    }
}

@Composable
private fun BatteryDashboardContent(
    uiState: BatteryUiState,
    paddingValues: PaddingValues,
    onToggleShowAllApps: () -> Unit,
    onOpenUsageAccessSettings: () -> Unit,
) {
    val report = uiState.batteryReport
    if (report == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            Text(
                text = uiState.errorMessage ?: "Unavailable",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = paddingValues.calculateTopPadding() + 8.dp,
            end = 16.dp,
            bottom = paddingValues.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        uiState.errorMessage?.let { error ->
            item {
                BatteryCard(
                    title = "Status",
                    subtitle = error,
                ) {
                    Text(
                        text = "Showing the latest successful data snapshot.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        item {
            BatterySummaryCard(
                levelPercent = report.levelPercent,
                remainingRuntimeMillis = report.remainingRuntimeMillis,
                estimatedFullRuntimeMillis = report.estimatedFullRuntimeMillis,
            )
        }

        item {
            SinceLastChargeCard(
                sinceLastChargeMillis = report.sinceLastChargeMillis,
                recordSinceLastChargeMillis = report.recordSinceLastChargeMillis,
                batteryHealth = report.batteryHealth,
                chargeCycles = report.chargeCycles,
            )
        }

        item {
            BatteryInsightsCard(insights = report.insights)
        }

        item {
            AppUsageCard(
                usagePermissionGranted = report.usagePermissionGranted,
                appUsage = uiState.visibleAppUsage,
                canExpandApps = uiState.canExpandApps,
                showAllApps = uiState.showAllApps,
                onToggleShowAllApps = onToggleShowAllApps,
                onOpenUsageAccessSettings = onOpenUsageAccessSettings,
            )
        }
    }
}

@Composable
private fun BatteryDashboardSkeleton(paddingValues: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = paddingValues.calculateTopPadding() + 8.dp,
            end = 16.dp,
            bottom = paddingValues.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { SkeletonCard("Battery Summary") }
        item { SkeletonCard("Since Last Charge") }
        item { SkeletonCard("Battery Insights") }
        item { SkeletonCard("Application Battery Usage") }
    }
}

@Composable
private fun SkeletonCard(title: String) {
    BatteryCard(title = title) {
        repeat(4) {
            ShimmerPlaceholder(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp),
            )
        }
    }
}
