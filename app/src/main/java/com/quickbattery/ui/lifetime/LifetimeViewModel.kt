package com.quickbattery.ui.lifetime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickbattery.domain.lifetime.LifetimeInput
import com.quickbattery.domain.lifetime.LifetimeStatisticsCalculator
import com.quickbattery.domain.repository.BatteryRepository
import com.quickbattery.domain.repository.PurchaseDateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Coordinates the Battery Lifetime screen. It only orchestrates data: it observes the persisted
 * purchase date, pulls the latest battery report, and delegates every computation to
 * [LifetimeStatisticsCalculator]. No calculations happen here.
 */
@HiltViewModel
class LifetimeViewModel @Inject constructor(
    private val batteryRepository: BatteryRepository,
    private val purchaseDateRepository: PurchaseDateRepository,
    private val calculator: LifetimeStatisticsCalculator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LifetimeUiState())
    val uiState: StateFlow<LifetimeUiState> = _uiState.asStateFlow()

    private var purchaseDateMillis: Long? = null
    private var loadRequested = false
    private var recomputeJob: Job? = null

    init {
        viewModelScope.launch {
            purchaseDateRepository.purchaseDateMillis.collect { millis ->
                purchaseDateMillis = millis
                _uiState.update { it.copy(purchaseDateMillis = millis) }
                if (loadRequested) {
                    recompute()
                }
            }
        }
    }

    /** Triggers the (lazy) load of battery data and statistics when the screen is opened. */
    fun onScreenOpened() {
        loadRequested = true
        recompute()
    }

    /** Persists a new purchase date. The collector recalculates statistics immediately after. */
    fun setPurchaseDate(millis: Long) {
        viewModelScope.launch {
            purchaseDateRepository.setPurchaseDate(millis)
        }
    }

    private fun recompute() {
        val purchase = purchaseDateMillis
        if (purchase == null) {
            _uiState.update { it.copy(isLoading = false, statistics = null) }
            return
        }

        recomputeJob?.cancel()
        recomputeJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val statistics = runCatching {
                val report = batteryRepository.getBatteryReport()
                calculator.calculate(
                    LifetimeInput(
                        purchaseDateMillis = purchase,
                        nowMillis = System.currentTimeMillis(),
                        chargeCycles = report.chargeCycles,
                        batteryHealth = report.batteryHealth,
                    ),
                )
            }.getOrNull()
            _uiState.update { it.copy(isLoading = false, statistics = statistics) }
        }
    }
}
