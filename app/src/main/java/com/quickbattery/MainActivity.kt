package com.quickbattery

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickbattery.data.provider.BatteryMonitorService
import com.quickbattery.ui.BatteryDashboardScreen
import com.quickbattery.ui.BatteryViewModel
import com.quickbattery.ui.lifetime.BatteryLifetimeScreen
import com.quickbattery.ui.lifetime.LifetimeViewModel
import com.quickbattery.ui.lifetime.PurchaseDatePickerDialog
import com.quickbattery.ui.theme.QuickBatteryTheme
import com.quickbattery.ui.theme.ThemeViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The monitor service runs regardless; the notification is simply hidden if denied.
            BatteryMonitorService.start(this)
            maybePromptBatteryOptimization()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ensureMonitoring()

        setContent {
            val themeViewModel: ThemeViewModel = hiltViewModel()
            val themeVariant by themeViewModel.themeVariant.collectAsStateWithLifecycle()

            QuickBatteryTheme(variant = themeVariant) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    QuickBatteryApp(
                        onOpenUsageAccessSettings = ::openUsageAccessSettings,
                        onToggleTheme = themeViewModel::toggleTheme,
                    )
                }
            }
        }
    }

    private fun openUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startActivity(intent)
    }

    // Guarantees the always-on monitor is running whenever the app is opened and, on Android 13+,
    // requests the notification permission the foreground service needs to surface its status.
    private fun ensureMonitoring() {
        BatteryMonitorService.start(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        maybePromptBatteryOptimization()
    }

    // OEM battery optimization can freeze the monitor service and drop battery broadcasts, which is
    // the root cause of "time since last charge" stalling in the background. Prompt the user once to
    // exempt the app so the service survives. Guarded by a flag so it never nags on later launches.
    @SuppressLint("BatteryLife")
    private fun maybePromptBatteryOptimization() {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            return
        }

        val prefs = getSharedPreferences(MONITOR_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BATTERY_OPT_PROMPTED, false)) {
            return
        }
        prefs.edit().putBoolean(KEY_BATTERY_OPT_PROMPTED, true).apply()

        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", packageName, null),
        )
        val handled = runCatching { startActivity(intent) }.isSuccess
        if (!handled) {
            // Fall back to the general list if the direct request isn't available on this device.
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}

private const val MONITOR_PREFS = "monitor_prefs"
private const val KEY_BATTERY_OPT_PROMPTED = "battery_opt_prompted"

private enum class Screen { Dashboard, Lifetime }

@Composable
private fun QuickBatteryApp(
    onOpenUsageAccessSettings: () -> Unit,
    onToggleTheme: () -> Unit,
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
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        batteryViewModel.onForegroundChanged(isInForeground = true)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        batteryViewModel.onForegroundChanged(isInForeground = false)
    }

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
                onToggleTheme = onToggleTheme,
                onEditPurchaseDate = {
                    openLifetimeAfterSave = false
                    showDatePicker = true
                },
                onResetCalculationData = {
                    lifetimeViewModel.resetCalculationData()
                    batteryViewModel.refresh()
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
