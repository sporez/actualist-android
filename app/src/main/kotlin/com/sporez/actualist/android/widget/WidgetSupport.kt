package com.sporez.actualist.android.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.unit.ColorProvider
import com.sporez.actualist.android.AppContainer
import com.sporez.actualist.android.AppShortcuts
import com.sporez.actualist.android.MainActivity
import com.sporez.actualist.android.data.ThemeMode
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.transactions.TransactionQuery
import com.sporez.actualist.core.widgets.AttentionSummary
import com.sporez.actualist.core.widgets.WidgetShaping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Widget colours for the app's theme setting (or the system's, when the app follows it). */
class WidgetPalette(val dark: Boolean) {
    val background = Color(if (dark) 0xFF1E1F22 else 0xFFF6F6F8)
    val text = ColorProvider(Color(if (dark) 0xFFE6E6E6 else 0xFF1B1B1F))
    val muted = ColorProvider(Color(if (dark) 0xFFA0A0A0 else 0xFF5F6066))
    val danger = ColorProvider(Color(if (dark) 0xFFFF8A80 else 0xFFB3261E))
    val positive = ColorProvider(Color(if (dark) 0xFF7FD6A0 else 0xFF1B7A43))

    companion object {
        fun resolve(context: Context, mode: ThemeMode): WidgetPalette {
            val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return WidgetPalette(
                when (mode) {
                    ThemeMode.SYSTEM -> systemDark
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                },
            )
        }
    }
}

/** A tap target for a widget: MainActivity with the launcher-shortcut route extra. */
fun widgetOpenAction(context: Context, route: String?) = actionStartActivity(
    Intent(context, MainActivity::class.java).setAction("com.sporez.actualist.android.widget.OPEN.${route ?: "home"}").also {
        if (route != null) it.putExtra(AppShortcuts.EXTRA_ROUTE, route)
    },
)

/** Common widget surface: themed background, padding, and a tap that opens [route]. */
@Composable
fun WidgetFrame(context: Context, palette: WidgetPalette, route: String?, content: @Composable () -> Unit) {
    Column(
        GlanceModifier.fillMaxSize().background(palette.background).padding(14.dp).clickable(widgetOpenAction(context, route)),
    ) { content() }
}

data class WidgetAccountRow(val name: String, val balance: Long)
data class WidgetTransactionRow(val payee: String, val amount: Long, val date: String)

/** Everything the three list widgets show; null from [load] means no budget is selected. */
data class WidgetPanels(
    val budgetName: String,
    val accounts: List<WidgetAccountRow>,
    val recent: List<WidgetTransactionRow>,
    val attention: AttentionSummary,
    val hideAmounts: Boolean,
    val dark: Boolean,
)

object WidgetPanelData {
    private val dateFormat = DateTimeFormatter.ofPattern("MMM d")

    suspend fun load(context: Context, container: AppContainer, today: LocalDate = LocalDate.now()): WidgetPanels? = withContext(Dispatchers.IO) {
        val settings = container.settings.settings.value
        val id = settings.selectedBudgetId ?: return@withContext null
        val session = container.sessions.acquire(id) { container.library.open(id) ?: error("The selected budget is no longer on this device.") }
        try {
            val month = Months.id(today.year, today.monthValue)
            session.read {
                val overspent = WidgetShaping.overspent(budget.fetchBudgetMonth(month).categories)
                WidgetPanels(
                    budgetName = name,
                    accounts = WidgetShaping.onBudgetAccounts(ledger.accounts(), 8).map { WidgetAccountRow(it.name, it.balance) },
                    recent = WidgetShaping.recent(ledger.transactions(TransactionQuery(limit = 20)), 6).map {
                        WidgetTransactionRow(it.displayPayee.ifBlank { "No payee" }, it.amount, it.date?.format(dateFormat) ?: "")
                    },
                    attention = AttentionSummary(overspent, ledger.uncategorizedCount()),
                    hideAmounts = settings.hideAmounts,
                    dark = WidgetPalette.resolve(context, settings.themeMode).dark,
                )
            }
        } finally {
            container.sessions.release(session)
        }
    }
}

/** Entry point for every widget the app ships. */
object Widgets {
    suspend fun updateAll(context: Context) {
        runCatching { BudgetWidget().updateAll(context) }
        runCatching { AccountBalancesWidget().updateAll(context) }
        runCatching { RecentTransactionsWidget().updateAll(context) }
        runCatching { NeedsAttentionWidget().updateAll(context) }
        runCatching { CategoryBalanceWidget().updateAll(context) }
        runCatching { MonthOverviewWidget().updateAll(context) }
        runCatching { NetWorthWidget().updateAll(context) }
        runCatching { QuickActionsWidget().updateAll(context) }
    }
}
