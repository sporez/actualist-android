package com.sporez.actualist.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sporez.actualist.core.store.BudgetSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/** A screen's data: loading, loaded, or a failure to show (never a fabricated value). */
sealed class Loadable<out T> {
    data object Loading : Loadable<Nothing>()
    data class Ready<T>(val value: T) : Loadable<T>()
    data class Failed(val message: String) : Loadable<Nothing>()
}

/**
 * Reloads [load] from the database whenever the session changes (any write or sync) or
 * [input] changes. Reads always come from the local database, the source of truth.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <I, T> ViewModel.reloading(session: BudgetSession, input: Flow<I>, load: BudgetSession.(I) -> T): StateFlow<Loadable<T>> =
    combine(session.revision, input) { _, value -> value }
        .flatMapLatest { value ->
            flow<Loadable<T>> {
                emit(
                    try {
                        Loadable.Ready(session.read { load(value) })
                    } catch (error: Exception) {
                        Loadable.Failed(error.message ?: "Something went wrong reading the budget.")
                    },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable.Loading)

/** A one-line message the screen shows after a write fails (or succeeds, when useful). */
class MessageState {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun show(text: String) {
        _message.value = text
    }
    fun clear() {
        _message.value = null
    }
}
