package com.quickbattery.ui.lifetime

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quickbattery.domain.model.BatteryHealthUi
import com.quickbattery.domain.model.ChargingHabitsUi
import com.quickbattery.domain.model.LifetimeProjectionUi
import com.quickbattery.domain.model.LifetimeStatistics
import com.quickbattery.domain.model.PhoneAgeUi
import com.quickbattery.domain.model.TimelineEntryUi
import com.quickbattery.domain.model.UsageProfileUi
import com.quickbattery.ui.components.BatteryCard
import com.quickbattery.ui.components.MetricRow
import com.quickbattery.ui.components.ShimmerPlaceholder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryLifetimeScreen(
    uiState: LifetimeUiState,
    onBack: () -> Unit,
    onToggleTheme: () -> Unit,
    onEditPurchaseDate: () -> Unit,
    onResetCalculationData: () -> Unit,
    loggingEnabled: Boolean,
    onLoggingClick: () -> Unit,
    onLoggingLongClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Battery Lifetime",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        AnimatedContent(
            targetState = uiState.isLoading || uiState.statistics == null,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.fillMaxSize(),
            label = "lifetime-loading",
        ) { loading ->
            if (loading) {
                LifetimeSkeleton(paddingValues = innerPadding)
            } else {
                LifetimeContent(
                    statistics = requireNotNull(uiState.statistics),
                    paddingValues = innerPadding,
                    onToggleTheme = onToggleTheme,
                    onEditPurchaseDate = onEditPurchaseDate,
                    onResetCalculationData = onResetCalculationData,
                    loggingEnabled = loggingEnabled,
                    onLoggingClick = onLoggingClick,
                    onLoggingLongClick = onLoggingLongClick,
                )
            }
        }
    }
}

@Composable
private fun LifetimeContent(
    statistics: LifetimeStatistics,
    paddingValues: PaddingValues,
    onToggleTheme: () -> Unit,
    onEditPurchaseDate: () -> Unit,
    onResetCalculationData: () -> Unit,
    loggingEnabled: Boolean,
    onLoggingClick: () -> Unit,
    onLoggingLongClick: () -> Unit,
) {
    // Rotate the data-derived facts on every load without altering the deterministic calculation.
    val rotatedFacts = remember(statistics) { statistics.facts.shuffled() }

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
        item { PhoneAgeCard(statistics.phoneAge) }
        item { ChargingHabitsCard(statistics.chargingHabits) }
        item { ProjectionCard(statistics.projection) }
        item { HealthCard(statistics.health) }
        item { UsageProfileCard(statistics.usageProfile) }
        item { FactsCard(rotatedFacts) }
        item { TimelineCard(statistics.timeline) }
        item {
            SettingsButtons(
                loggingEnabled = loggingEnabled,
                onToggleTheme = onToggleTheme,
                onEditPurchaseDate = onEditPurchaseDate,
                onResetCalculationData = onResetCalculationData,
                onLoggingClick = onLoggingClick,
                onLoggingLongClick = onLoggingLongClick,
            )
        }
    }
}

@Composable
private fun SettingsButtons(
    loggingEnabled: Boolean,
    onToggleTheme: () -> Unit,
    onEditPurchaseDate: () -> Unit,
    onResetCalculationData: () -> Unit,
    onLoggingClick: () -> Unit,
    onLoggingLongClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SETTINGS_GRID_SPACING)) {
        SettingsButtonRow {
            OutlinedButton(
                onClick = onToggleTheme,
                modifier = settingsCell(),
                contentPadding = SETTINGS_BUTTON_PADDING,
            ) {
                SettingsButtonLabel("Change Theme")
            }
            Button(
                onClick = onEditPurchaseDate,
                modifier = settingsCell(),
                contentPadding = SETTINGS_BUTTON_PADDING,
            ) {
                SettingsButtonLabel("Edit Purchase Date")
            }
        }
        SettingsButtonRow {
            OutlinedButton(
                onClick = onResetCalculationData,
                modifier = settingsCell(),
                contentPadding = SETTINGS_BUTTON_PADDING,
            ) {
                SettingsButtonLabel("Reset Calculation Data")
            }
            LoggingButton(
                loggingEnabled = loggingEnabled,
                onClick = onLoggingClick,
                onLongClick = onLoggingLongClick,
                modifier = settingsCell(),
            )
        }
    }
}

// Both cells of a row share the height of the taller one, so a wrapped label never leaves its
// neighbour looking shorter.
@Composable
private fun SettingsButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(SETTINGS_GRID_SPACING),
        content = content,
    )
}

private fun RowScope.settingsCell(): Modifier = Modifier.weight(1f).fillMaxHeight()

@Composable
private fun SettingsButtonLabel(text: String) {
    Text(text = text, textAlign = TextAlign.Center)
}

/**
 * Material buttons have no long-press, so this one is drawn by hand to match OutlinedButton while
 * idle, and fills in while a capture is running. Long-press arms or discards logging; a tap
 * exports once armed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LoggingButton(
    loggingEnabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = ButtonDefaults.outlinedShape
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = shape,
        color = if (loggingEnabled) colors.tertiaryContainer else Color.Transparent,
        contentColor = if (loggingEnabled) colors.onTertiaryContainer else colors.primary,
        border = if (loggingEnabled) null else BorderStroke(1.dp, colors.outline),
        modifier = modifier
            .defaultMinSize(minHeight = ButtonDefaults.MinHeight)
            .clip(shape)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = if (loggingEnabled) "Export logs" else null,
                onLongClickLabel = if (loggingEnabled) "Disable logging" else "Enable logging",
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(SETTINGS_BUTTON_PADDING),
        ) {
            Text(
                text = if (loggingEnabled) "Export logs" else "Enable logging",
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private val SETTINGS_GRID_SPACING = 8.dp
private val SETTINGS_BUTTON_PADDING = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

@Composable
private fun PhoneAgeCard(phoneAge: PhoneAgeUi) {
    BatteryCard(title = "Phone Age") {
        Text(
            text = phoneAge.ageDisplay,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
        )
        MetricRow(label = "Purchase Date", value = phoneAge.purchaseDateLabel)
        MetricRow(label = "Current Date", value = phoneAge.currentDateLabel)
    }
}

@Composable
private fun ChargingHabitsCard(habits: ChargingHabitsUi) {
    BatteryCard(title = "Charging Habits") {
        if (!habits.available) {
            UnavailableNote("Charge cycle data isn't reported by this device.")
            return@BatteryCard
        }
        MetricRow(label = "Average per day", value = habits.perDay, emphasized = true)
        MetricRow(label = "Average per month", value = habits.perMonth)
        MetricRow(label = "Estimated per year", value = habits.perYear)
        MetricRow(label = "Days between cycles", value = habits.averageDaysBetweenCycles)
    }
}

@Composable
private fun ProjectionCard(projection: LifetimeProjectionUi) {
    BatteryCard(
        title = "Battery Lifetime Projection",
        subtitle = "Projections are estimates only, based on your current charging rate.",
    ) {
        if (!projection.available) {
            UnavailableNote("A projection needs charge cycle data, which isn't available.")
            return@BatteryCard
        }
        projection.entries.forEachIndexed { index, entry ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = entry.cycleLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                MetricRow(label = "Expected", value = entry.expectedDate)
                MetricRow(label = "Remaining", value = entry.remaining)
            }
            if (index != projection.entries.lastIndex) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun HealthCard(health: BatteryHealthUi) {
    BatteryCard(title = "Battery Health") {
        if (!health.available) {
            UnavailableNote("Battery health isn't reported by this device.")
        }
        MetricRow(label = "Battery Health", value = health.health, emphasized = true)
        MetricRow(label = "Charge Cycles", value = health.chargeCycles)
        MetricRow(label = "Phone Age", value = health.phoneAge)
        MetricRow(label = "Estimated Usage Rate", value = health.estimatedUsageRate)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Interpretation",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
                Text(
                    text = health.interpretation,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun UsageProfileCard(profile: UsageProfileUi) {
    BatteryCard(title = "Battery Usage Profile") {
        if (!profile.available) {
            UnavailableNote("Usage classification needs charge cycle data.")
            return@BatteryCard
        }
        Text(
            text = profile.classification,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = profile.explanation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
        )
    }
}

@Composable
private fun FactsCard(facts: List<String>) {
    BatteryCard(title = "Battery Facts") {
        if (facts.isEmpty()) {
            UnavailableNote("No facts can be derived yet.")
            return@BatteryCard
        }
        facts.forEach { fact ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary),
                )
                Text(
                    text = fact,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun TimelineCard(timeline: List<TimelineEntryUi>) {
    BatteryCard(title = "Lifetime Timeline") {
        var started by remember(timeline) { mutableStateOf(false) }
        LaunchedEffect(timeline) { started = true }

        timeline.forEachIndexed { index, entry ->
            val progress by animateFloatAsState(
                targetValue = if (started) 1f else 0f,
                animationSpec = tween(
                    durationMillis = 420,
                    delayMillis = index * 110,
                    easing = FastOutSlowInEasing,
                ),
                label = "timeline-$index",
            )
            TimelineRow(
                entry = entry,
                isLast = index == timeline.lastIndex,
                modifier = Modifier.graphicsLayer {
                    alpha = progress
                    translationY = (1f - progress) * 36f
                },
            )
        }
    }
}

@Composable
private fun TimelineRow(
    entry: TimelineEntryUi,
    isLast: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(24.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary),
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .width(2.dp)
                        .height(40.dp)
                        .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f)),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
            )
        }
    }
}

@Composable
private fun UnavailableNote(message: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Unavailable",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
        )
    }
}

@Composable
private fun LifetimeSkeleton(paddingValues: PaddingValues) {
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
        skeletonItems(
            listOf(
                "Phone Age",
                "Charging Habits",
                "Battery Lifetime Projection",
                "Battery Health",
                "Lifetime Timeline",
            ),
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.skeletonItems(titles: List<String>) {
    titles.forEach { title ->
        item {
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
    }
}
