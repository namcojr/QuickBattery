package com.quickbattery

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickbattery.ui.BatteryDashboardScreen
import com.quickbattery.ui.BatteryViewModel
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
                    val viewModel: BatteryViewModel = hiltViewModel()
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                    BatteryDashboardScreen(
                        uiState = uiState,
                        onRefresh = viewModel::refresh,
                        onToggleShowAllApps = viewModel::toggleAppListExpanded,
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
