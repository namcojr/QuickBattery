package com.quickbattery.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Persists the user-provided phone purchase date so lifetime statistics survive restarts.
 *
 * The value is a UTC epoch-millis timestamp, or `null` when the user has never configured it.
 */
interface PurchaseDateRepository {
    val purchaseDateMillis: Flow<Long?>

    suspend fun setPurchaseDate(millis: Long)
}
