package com.sporez.actualist.android.widget.config

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.sporez.actualist.android.ActualistApplication
import com.sporez.actualist.android.AppContainer
import com.sporez.actualist.android.ui.theme.ActualistTheme
import com.sporez.actualist.android.widget.Widgets
import com.sporez.actualist.android.widget.WidgetQuickAction
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.widgets.WidgetChoices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** The three widgets Actualist lets the user configure, with how many items each takes. */
enum class WidgetConfigKind(val title: String, val hint: String, val limit: Int) {
    QUICK_ACTIONS("Quick Actions", "Choose four actions. They show left to right in the order you tap them.", WidgetChoices.QUICK_ACTION_CAPACITY),
    CATEGORY_BALANCES("Category Balances", "Choose up to 16 categories, in the order you want them. Leave it empty for the most overspent one.", WidgetChoices.CATEGORY_LIMIT),
    ACCOUNT_BALANCES("Account Balances", "Choose accounts in the order you want them. Leave it empty for the on-budget accounts.", WidgetChoices.ACCOUNT_LIMIT),
}

data class ConfigOption(val id: String, val title: String, val subtitle: String)

object WidgetConfigOptions {
    /** What can be chosen for [kind]; empty when no budget is open. */
    suspend fun load(container: AppContainer, kind: WidgetConfigKind, today: LocalDate = LocalDate.now()): List<ConfigOption> = withContext(Dispatchers.IO) {
        if (kind == WidgetConfigKind.QUICK_ACTIONS) return@withContext WidgetQuickAction.entries.map { ConfigOption(it.id, it.title, "") }
        val budgetId = container.settings.settings.value.selectedBudgetId ?: return@withContext emptyList()
        val session = container.sessions.acquire(budgetId) { container.library.open(budgetId) ?: error("The selected budget is no longer on this device.") }
        try {
            session.read {
                if (kind == WidgetConfigKind.CATEGORY_BALANCES) {
                    budget.fetchBudgetMonth(Months.id(today.year, today.monthValue)).categories
                        .filter { !it.isIncome && !it.hidden }
                        .map { ConfigOption(it.id, it.name, "") }
                } else {
                    ledger.accounts().filter { !it.closed }.sortedBy { it.sortOrder }
                        .map { ConfigOption(it.id, it.name, if (it.offBudget) "Off budget" else "") }
                }
            }
        } catch (failure: Exception) {
            emptyList()
        } finally {
            container.sessions.release(session)
        }
    }
}

/** The configuration screen a launcher opens when one of these widgets is placed (android:configure). */
abstract class WidgetConfigActivity : ComponentActivity() {
    protected abstract val kind: WidgetConfigKind

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val container = (application as ActualistApplication).container
        val store = WidgetConfigStore(this)
        val initial = store.get(appWidgetId) ?: if (kind == WidgetConfigKind.QUICK_ACTIONS) WidgetQuickAction.defaults.map { it.id } else emptyList()
        setContent {
            val settings by container.settings.settings.collectAsState()
            ActualistTheme(settings.themeMode, settings.namedTheme, settings.displaySize) {
                Surface(Modifier.fillMaxSize()) {
                    WidgetConfigScreen(
                        kind = kind,
                        initial = initial,
                        loadOptions = { WidgetConfigOptions.load(container, kind) },
                        onCancel = { finish() },
                        onSave = { chosen ->
                            if (chosen.isEmpty()) store.clear(appWidgetId) else store.set(appWidgetId, chosen)
                            lifecycleScope.launch {
                                setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                                Widgets.updateAll(applicationContext)
                                finish()
                            }
                        },
                    )
                }
            }
        }
    }
}

class QuickActionsConfigActivity : WidgetConfigActivity() {
    override val kind: WidgetConfigKind get() = WidgetConfigKind.QUICK_ACTIONS
}

class CategoryBalanceConfigActivity : WidgetConfigActivity() {
    override val kind: WidgetConfigKind get() = WidgetConfigKind.CATEGORY_BALANCES
}

class AccountBalancesConfigActivity : WidgetConfigActivity() {
    override val kind: WidgetConfigKind get() = WidgetConfigKind.ACCOUNT_BALANCES
}

@Composable
private fun WidgetConfigScreen(
    kind: WidgetConfigKind,
    initial: List<String>,
    loadOptions: suspend () -> List<ConfigOption>,
    onCancel: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var options by remember { mutableStateOf<List<ConfigOption>?>(null) }
    var selection by remember { mutableStateOf(initial) }
    LaunchedEffect(kind) { options = loadOptions() }
    Column(Modifier.systemBarsPadding().padding(16.dp).testTag("widget-config"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(kind.title, style = MaterialTheme.typography.titleLarge)
        Text(kind.hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val shown = options
        if (shown != null && shown.isEmpty()) {
            Text("Open Actualist and choose a budget first.", Modifier.testTag("widget-config-empty"))
        }
        LazyColumn(Modifier.weight(1f).testTag("widget-config-list")) {
            items(shown.orEmpty(), key = { it.id }) { option ->
                val position = selection.indexOf(option.id)
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { selection = WidgetChoices.toggle(selection, option.id, kind.limit) }
                        .padding(vertical = 6.dp)
                        .testTag("widget-config-option-${option.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = position >= 0, onCheckedChange = null)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(option.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (option.subtitle.isNotEmpty()) Text(option.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (position >= 0) Text("${position + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("widget-config-cancel")) { Text("Cancel") }
            Button(onClick = { onSave(selection) }, modifier = Modifier.testTag("widget-config-save")) { Text("Save") }
        }
    }
}
