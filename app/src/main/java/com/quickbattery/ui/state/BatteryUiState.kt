package com.quickbattery.ui.state

import androidx.compose.runtime.Immutable
import com.quickbattery.domain.model.AppBatteryUsage
import com.quickbattery.domain.model.BatteryReport

@Immutable
data class BatteryUiState(
    val isLoading: Boolean = true,
    val batteryReport: BatteryReport? = null,
    val showAllApps: Boolean = false,
    val errorMessage: String? = null,
) {
    val visibleAppUsage: List<AppBatteryUsage>
        get() {
            val apps = batteryReport?.appUsage.orEmpty()
            return if (showAllApps) apps else apps.take(MAX_COLLAPSED_APPS)
        }

    val canExpandApps: Boolean
        get() = (batteryReport?.appUsage?.size ?: 0) > MAX_COLLAPSED_APPS

    private companion object {
        private const val MAX_COLLAPSED_APPS = 10
    }
}
