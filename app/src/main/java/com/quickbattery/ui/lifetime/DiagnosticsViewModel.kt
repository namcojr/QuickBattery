package com.quickbattery.ui.lifetime

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickbattery.data.provider.BatteryDiagnosticsLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Drives the diagnostic logging toggle: enable, discard, and export to a user-chosen file. */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val loggingEnabled: StateFlow<Boolean> = BatteryDiagnosticsLog.enabled(context)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun enableLogging() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { BatteryDiagnosticsLog.enable(context) }
            _messages.tryEmit("Logging enabled")
        }
    }

    fun discardLogging() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { BatteryDiagnosticsLog.disable(context) }
            _messages.tryEmit("Logging disabled")
        }
    }

    fun suggestedExportFileName(): String = BatteryDiagnosticsLog.suggestedFileName()

    /** A null [uri] means the file picker was cancelled: logging simply carries on. */
    fun exportLogs(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val exported = withContext(Dispatchers.IO) { BatteryDiagnosticsLog.export(context, uri) }
            _messages.tryEmit(if (exported) "Logs exported" else "Export failed, logging is still on")
        }
    }
}
