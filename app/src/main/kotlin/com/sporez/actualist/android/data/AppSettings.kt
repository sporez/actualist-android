package com.sporez.actualist.android.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Actualist's named themes (ActualistThemeOption); `null` in [Settings.namedTheme] means System/Dark/Light. */
enum class NamedTheme(val title: String, val dark: Boolean) {
    ACTUAL_PURPLE("Actual Purple (dark)", true),
    ACTUAL_PURPLE_LIGHT("Actual Purple (light)", false),
    COASTAL_SAGE_LIGHT("Coastal Sage (light)", false),
    AMETHYST_HAZE("Amethyst Haze (dark)", true),
    EMBER_AMBER("Ember Amber (dark)", true),
    BLUE_CURRENT("Blue Current (dark)", true),
    ROSE_QUARTZ("Rose Quartz (dark)", true),
    WHY_NAB("Why Nab (dark)", true),
}

/** Actualist's display density steps; [fontScale] multiplies text size app-wide. */
enum class DisplaySize(val title: String, val fontScale: Float) {
    DENSE("Dense", 0.88f),
    COMPACT("Compact", 0.94f),
    COMFORTABLE("Comfortable", 1f),
    LARGE("Large", 1.15f),
}

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
    /** A named theme overrides [themeMode] when set. */
    val namedTheme: NamedTheme? = null,
    val displaySize: DisplaySize = DisplaySize.COMFORTABLE,
    /** Actualist's "Green Income Amounts". Defaults on here, which is how Android has always shown positive amounts. */
    val greenIncomeAmounts: Boolean = true,
    /** Actualist's "Use Sample Values": amounts are replaced by made-up ones on screen only. */
    val sampleValues: Boolean = false,
    /** Account preselected for new transactions; null keeps the automatic choice. */
    val defaultAccountId: String? = null,
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
        namedTheme = prefs.getString("named_theme", null)?.let { runCatching { NamedTheme.valueOf(it) }.getOrNull() },
        displaySize = prefs.getString("display_size", null)?.let { runCatching { DisplaySize.valueOf(it) }.getOrNull() } ?: DisplaySize.COMFORTABLE,
        greenIncomeAmounts = prefs.getBoolean("green_income_amounts", true),
        sampleValues = prefs.getBoolean("sample_values", false),
        defaultAccountId = prefs.getString("default_account", null),
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
            .putString("named_theme", next.namedTheme?.name)
            .putString("display_size", next.displaySize.name)
            .putBoolean("green_income_amounts", next.greenIncomeAmounts)
            .putBoolean("sample_values", next.sampleValues)
            .putString("default_account", next.defaultAccountId)
            .apply()
        _settings.value = next
    }
}
