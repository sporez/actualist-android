package com.sporez.actualist.android.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Actualist's App Switcher privacy modes, mapped to Android's recents thumbnail controls. */
enum class AppSwitcherPrivacy(val title: String, val detail: String) {
    OFF("Off", "Never hide app contents in the recent apps screen."),
    WHEN_BACKGROUNDED("When backgrounded", "Hide contents in the recent apps screen; screenshots still work."),
    ALWAYS("Always", "Hide contents in the recent apps screen and block screenshots and screen recording."),
}

/** Non-secret app preferences. Credentials live in [SecretStore], never here. */
data class Settings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val selectedBudgetId: String? = null,
    val serverUrl: String? = null,
    val hideAmounts: Boolean = false,
    val appSwitcherPrivacy: AppSwitcherPrivacy = AppSwitcherPrivacy.OFF,
    /** Actualist's "Show Total Assigned": the month's assigned total under the Budget title. */
    val showTotalAssigned: Boolean = false,
    /** Actualist's "Hide Carryover Arrows": no rollover badge on the available pills. */
    val hideCarryoverArrows: Boolean = false,
    /** Actualist's "Swipe Between Months". */
    val swipeBetweenMonths: Boolean = true,
    /** Months shown side by side on wide screens: 0 is Auto (three months), otherwise 1 to 5. */
    val monthsShown: Int = 0,
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
        appSwitcherPrivacy = prefs.getString("app_switcher_privacy", null)?.let { runCatching { AppSwitcherPrivacy.valueOf(it) }.getOrNull() } ?: AppSwitcherPrivacy.OFF,
        showTotalAssigned = prefs.getBoolean("show_total_assigned", false),
        hideCarryoverArrows = prefs.getBoolean("hide_carryover_arrows", false),
        swipeBetweenMonths = prefs.getBoolean("swipe_between_months", true),
        monthsShown = prefs.getInt("months_shown", 0).coerceIn(0, 5),
    )

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString("theme", next.themeMode.name)
            .putString("selected_budget", next.selectedBudgetId)
            .putString("server_url", next.serverUrl)
            .putBoolean("hide_amounts", next.hideAmounts)
            .putString("app_switcher_privacy", next.appSwitcherPrivacy.name)
            .putBoolean("show_total_assigned", next.showTotalAssigned)
            .putBoolean("hide_carryover_arrows", next.hideCarryoverArrows)
            .putBoolean("swipe_between_months", next.swipeBetweenMonths)
            .putInt("months_shown", next.monthsShown)
            .apply()
        _settings.value = next
    }
}
