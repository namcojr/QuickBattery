package com.quickbattery.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickbattery.domain.repository.ThemeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Exposes the persisted theme selection and lets the UI cycle through the available palettes. */
@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val themeRepository: ThemeRepository,
) : ViewModel() {

    val themeVariant: StateFlow<ThemeVariant> = themeRepository.themeVariant
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ThemeVariant.Blue,
        )

    /** Persists the next palette in the cycle. */
    fun toggleTheme() {
        val next = themeVariant.value.next()
        viewModelScope.launch {
            themeRepository.setThemeVariant(next)
        }
    }
}
