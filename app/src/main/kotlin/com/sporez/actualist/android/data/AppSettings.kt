package com.sporez.actualist.android.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Non-secret app preferences. Credentials live in [SecretStore], never here. */
data class Settings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val selectedBudgetId: String? = null,
    val serverUrl: String? = null,
    val hideAmounts: Boolean = false,
)

class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("actualist_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private fun load() = Settings(
        themeMode = prefs.getString("theme", null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
        selectedBudgetId = prefs.getString("selected_budget", null),
        serverUrl = prefs.getString("server_url", null),
        hideAmounts = prefs.getBoolean("hide_amounts", false),
    )

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString("theme", next.themeMode.name)
            .putString("selected_budget", next.selectedBudgetId)
            .putString("server_url", next.serverUrl)
            .putBoolean("hide_amounts", next.hideAmounts)
            .apply()
        _settings.value = next
    }
}
