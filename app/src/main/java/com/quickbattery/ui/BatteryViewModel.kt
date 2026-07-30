package com.quickbattery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickbattery.domain.repository.BatteryRepository
import com.quickbattery.ui.state.BatteryUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@HiltViewModel
class BatteryViewModel @Inject constructor(
    private val repository: BatteryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BatteryUiState())
    val uiState: StateFlow<BatteryUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var autoRefreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh(silent: Boolean = false) {
        if (refreshJob?.isActive == true) {
            return
        }

        refreshJob = viewModelScope.launch {
            val shouldShowLoading = !silent || _uiState.value.batteryReport == null
            if (shouldShowLoading) {
                _uiState.update { state ->
                    state.copy(
                        isLoading = true,
                        errorMessage = null,
                    )
                }
            }

            runCatching { repository.getBatteryReport() }
                .onSuccess { report ->
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            batteryReport = report,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure {
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            batteryReport = state.batteryReport,
                            errorMessage = "Unable to load battery data on this device.",
                        )
                    }
                }
        }
    }

    private fun startAutoRefresh() {
        if (autoRefreshJob?.isActive == true) {
            return
        }

        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(AUTO_REFRESH_INTERVAL_MILLIS)
                refresh(silent = true)
            }
        }
    }

    fun onForegroundChanged(isInForeground: Boolean) {
        if (isInForeground) {
            startAutoRefresh()
        } else {
            autoRefreshJob?.cancel()
            autoRefreshJob = null
        }
    }

    fun toggleAppListExpanded() {
        _uiState.update { state ->
            if (!state.canExpandApps) {
                state
            } else {
                state.copy(showAllApps = !state.showAllApps)
            }
        }
    }

    private companion object {
        private const val AUTO_REFRESH_INTERVAL_MILLIS = 5L * 60L * 1000L
    }
}
