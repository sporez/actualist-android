package com.sporez.actualist.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sporez.actualist.android.ActualistApplication
import com.sporez.actualist.core.store.BudgetSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class AppState {
    data object Loading : AppState()
    data object Onboarding : AppState()
    data class Open(val session: BudgetSession) : AppState()
    data class Failed(val message: String) : AppState()
}

/**
 * Owns the open budget session for the activity. The selected budget is persisted, so a
 * restart or process death reopens the same budget from its local file without a server.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    val container = (application as ActualistApplication).container
    private val _state = MutableStateFlow<AppState>(AppState.Loading)
    val state: StateFlow<AppState> = _state.asStateFlow()

    init {
        val selected = container.settings.settings.value.selectedBudgetId
        if (selected == null) {
            _state.value = AppState.Onboarding
        } else {
            openSession { container.library.open(selected) ?: error("The selected budget is no longer on this device.") }
        }
    }

    fun openDemo() = openSession { container.library.openDemo() }

    fun openBudget(id: String) = openSession { container.library.open(id) ?: error("That budget is not on this device.") }

    /** Deletes the demo file and builds a fresh one for today. */
    fun resetDemo() {
        val current = (state.value as? AppState.Open)?.session
        _state.value = AppState.Loading
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                current?.close()
                container.library.delete(com.sporez.actualist.core.demo.DemoBudget.FILE_ID)
            }
            openSession { container.library.openDemo() }
        }
    }

    fun closeBudget() {
        val current = (state.value as? AppState.Open)?.session
        container.settings.update { it.copy(selectedBudgetId = null) }
        _state.value = AppState.Onboarding
        viewModelScope.launch(Dispatchers.IO) { current?.close() }
    }

    private fun openSession(load: () -> BudgetSession) {
        val previous = (state.value as? AppState.Open)?.session
        _state.value = AppState.Loading
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    previous?.close()
                    load()
                }
                container.settings.update { it.copy(selectedBudgetId = session.budgetId) }
                _state.value = AppState.Open(session)
            } catch (error: Exception) {
                _state.value = AppState.Failed(error.message ?: "The budget could not be opened.")
            }
        }
    }

    override fun onCleared() {
        (state.value as? AppState.Open)?.session?.close()
    }
}
