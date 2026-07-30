package com.quickbattery

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickbattery.ui.BatteryDashboardScreen
import com.quickbattery.ui.BatteryViewModel
import com.quickbattery.ui.lifetime.BatteryLifetimeScreen
import com.quickbattery.ui.lifetime.LifetimeViewModel
import com.quickbattery.ui.lifetime.PurchaseDatePickerDialog
import com.quickbattery.ui.theme.QuickBatteryTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            QuickBatteryTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    QuickBatteryApp(
                        onOpenUsageAccessSettings = ::openUsageAccessSettings,
                    )
                }
            }
        }
    }

    private fun openUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startActivity(intent)
    }
}

private enum class Screen { Dashboard, Lifetime }

@Composable
private fun QuickBatteryApp(
    onOpenUsageAccessSettings: () -> Unit,
) {
    val batteryViewModel: BatteryViewModel = hiltViewModel()
    val lifetimeViewModel: LifetimeViewModel = hiltViewModel()

    val batteryState by batteryViewModel.uiState.collectAsStateWithLifecycle()
    val lifetimeState by lifetimeViewModel.uiState.collectAsStateWithLifecycle()

    var currentScreen by remember { mutableStateOf(Screen.Dashboard) }
    var showDatePicker by remember { mutableStateOf(false) }
    // When the date picker is opened because no date exists yet, jump to the Lifetime
    // screen automatically once the user saves a date.
    var openLifetimeAfterSave by remember { mutableStateOf(false) }

    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.Lifetime) {
            lifetimeViewModel.onScreenOpened()
        }
    }

    // Refresh battery data every time the app comes to the foreground so returning
    // after a period of inactivity shows up-to-date data, just like pressing Refresh.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        batteryViewModel.refresh()
    }

    Crossfade(
        targetState = currentScreen,
        animationSpec = tween(durationMillis = 220),
        label = "screen",
    ) { screen ->
        when (screen) {
            Screen.Dashboard -> BatteryDashboardScreen(
                uiState = batteryState,
                onRefresh = batteryViewModel::refresh,
                onToggleShowAllApps = batteryViewModel::toggleAppListExpanded,
                onOpenUsageAccessSettings = onOpenUsageAccessSettings,
                onOpenLifetime = {
                    if (lifetimeState.hasPurchaseDate) {
                        currentScreen = Screen.Lifetime
                    } else {
                        openLifetimeAfterSave = true
                        showDatePicker = true
                    }
                },
            )

            Screen.Lifetime -> BatteryLifetimeScreen(
                uiState = lifetimeState,
                onBack = { currentScreen = Screen.Dashboard },
                onEditPurchaseDate = {
                    openLifetimeAfterSave = false
                    showDatePicker = true
                },
            )
        }
    }

    if (showDatePicker) {
        PurchaseDatePickerDialog(
            initialSelectedDateMillis = lifetimeState.purchaseDateMillis,
            onDismiss = {
                showDatePicker = false
                openLifetimeAfterSave = false
            },
            onConfirm = { millis ->
                lifetimeViewModel.setPurchaseDate(millis)
                showDatePicker = false
                if (openLifetimeAfterSave) {
                    currentScreen = Screen.Lifetime
                    openLifetimeAfterSave = false
                }
            },
        )
    }
}
