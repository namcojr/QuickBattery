package com.quickbattery.domain.repository

import com.quickbattery.ui.theme.ThemeVariant
import kotlinx.coroutines.flow.Flow

/**
 * Persists the user-selected color theme so the choice survives restarts.
 *
 * Defaults to [ThemeVariant.Blue] when the user has never picked a theme.
 */
interface ThemeRepository {
    val themeVariant: Flow<ThemeVariant>

    suspend fun setThemeVariant(variant: ThemeVariant)
}
