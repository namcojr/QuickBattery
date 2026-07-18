package com.quickbattery.ui.lifetime

import androidx.compose.runtime.Immutable
import com.quickbattery.domain.model.LifetimeStatistics

@Immutable
data class LifetimeUiState(
    val isLoading: Boolean = true,
    val purchaseDateMillis: Long? = null,
    val statistics: LifetimeStatistics? = null,
) {
    val hasPurchaseDate: Boolean
        get() = purchaseDateMillis != null
}
