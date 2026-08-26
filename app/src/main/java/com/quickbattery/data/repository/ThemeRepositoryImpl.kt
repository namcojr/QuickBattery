package com.quickbattery.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.quickbattery.domain.repository.ThemeRepository
import com.quickbattery.ui.theme.ThemeVariant
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "theme_preferences",
)

@Singleton
class ThemeRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ThemeRepository {

    override val themeVariant: Flow<ThemeVariant> =
        context.themeDataStore.data.map { preferences ->
            preferences[THEME_VARIANT_KEY]
                ?.let { name -> runCatching { ThemeVariant.valueOf(name) }.getOrNull() }
                ?: ThemeVariant.Blue
        }

    override suspend fun setThemeVariant(variant: ThemeVariant) {
        context.themeDataStore.edit { preferences ->
            preferences[THEME_VARIANT_KEY] = variant.name
        }
    }

    private companion object {
        val THEME_VARIANT_KEY = stringPreferencesKey("theme_variant")
    }
}
