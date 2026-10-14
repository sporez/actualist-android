package com.sporez.actualist.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sporez.actualist.android.ActualistApplication
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.server.RemoteFile
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
            openSession(selected) { container.library.open(selected) ?: error("The selected budget is no longer on this device.") }
        }
    }

    fun openDemo() = openSession(DemoBudget.FILE_ID) { container.library.openDemo() }

    fun openBudget(id: String) = openSession(id) { container.library.open(id) ?: error("That budget is not on this device.") }

    /** Deletes the demo file and builds a fresh one for today. */
    fun resetDemo() {
        val current = (state.value as? AppState.Open)?.session
        _state.value = AppState.Loading
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                current?.let(container.sessions::release)
                container.library.delete(DemoBudget.FILE_ID)
            }
            openSession(DemoBudget.FILE_ID) { container.library.openDemo() }
        }
    }

    private val _notice = MutableStateFlow<String?>(null)
    /** A message from the last background action (a failed reimport), shown in Settings. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

    /**
     * Actualist's "Reimport Budget": releases the open copy, downloads [remote] again (replacing the
     * local file and discarding unsynced changes), and reopens it. On failure the old copy is reopened.
     */
    fun reimportBudget(remote: RemoteFile, password: String?) {
        val current = (state.value as? AppState.Open)?.session ?: return
        val id = current.budgetId
        _notice.value = null
        _state.value = AppState.Loading
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                container.sessions.release(current)
                try {
                    container.server.download(remote, password)
                    null
                } catch (error: Exception) {
                    error.message ?: "The budget could not be downloaded."
                }
            }
            _notice.value = failure ?: "Budget reimported from the server."
            openBudget(id)
        }
    }

    fun closeBudget() {
        val current = (state.value as? AppState.Open)?.session
        container.settings.update { it.copy(selectedBudgetId = null) }
        _state.value = AppState.Onboarding
        viewModelScope.launch(Dispatchers.IO) { current?.let(container.sessions::release) }
    }

    /** Sessions come from the process-wide [com.sporez.actualist.android.sync.SessionHub], shared with background sync. */
    private fun openSession(budgetId: String, load: () -> BudgetSession) {
        val previous = (state.value as? AppState.Open)?.session
        _state.value = AppState.Loading
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    previous?.let(container.sessions::release)
                    container.sessions.acquire(budgetId, load)
                }
                container.settings.update { it.copy(selectedBudgetId = session.budgetId) }
                _state.value = AppState.Open(session)
            } catch (error: Exception) {
                _state.value = AppState.Failed(error.message ?: "The budget could not be opened.")
            }
        }
    }

    override fun onCleared() {
        (state.value as? AppState.Open)?.session?.let(container.sessions::release)
    }
}
