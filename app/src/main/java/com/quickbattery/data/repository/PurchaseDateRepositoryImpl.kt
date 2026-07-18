package com.quickbattery.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.quickbattery.domain.repository.PurchaseDateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.lifetimeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lifetime_preferences",
)

@Singleton
class PurchaseDateRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : PurchaseDateRepository {

    override val purchaseDateMillis: Flow<Long?> =
        context.lifetimeDataStore.data.map { preferences ->
            preferences[PURCHASE_DATE_KEY]?.takeIf { it > 0L }
        }

    override suspend fun setPurchaseDate(millis: Long) {
        context.lifetimeDataStore.edit { preferences ->
            preferences[PURCHASE_DATE_KEY] = millis
        }
    }

    private companion object {
        val PURCHASE_DATE_KEY = longPreferencesKey("purchase_date_millis")
    }
}
