package com.sporez.actualist.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.sporez.actualist.android.ActualistApplication
import com.sporez.actualist.android.AppContainer
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.widgets.WidgetShaping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Actualist's widget quick actions (WidgetQuickAction) that exist on Android, with the route each opens. */
enum class WidgetQuickAction(val id: String, val title: String, val route: String) {
    ADD_EXPENSE("add-expense", "Add expense", "transaction-new"),
    ADD_INCOME("add-income", "Add income", "transaction-new"),
    SPENDING("spending", "Spending", "spending"),
    BUDGET("budget", "Budget", "budget"),
    HISTORY("history", "History", "history"),
    ACCOUNTS("accounts", "Accounts", "accounts"),
    PAYEES("payees", "Payees", "payees"),
    RULES("rules", "Rules", "rules"),
    BANK_SYNC("bank-sync", "Bank sync", "bank-sync"),
    REPORTS("reports", "Reports", "reports"),
    SETTINGS("settings", "Settings", "settings"),
    ;

    companion object {
        /** Actualist's default four, in order. */
        val defaults = listOf(ADD_EXPENSE, BUDGET, SPENDING, ACCOUNTS)
    }
}

/** The selected category, or Actualist's default: the most overspent one, else the one with the most spent. */
data class CategoryBalanceData(val budgetName: String, val categoryId: String, val name: String, val balance: Long, val budgeted: Long, val spent: Long)

/** A month's totals for the Month Overview widget. */
data class MonthOverviewData(
    val budgetName: String,
    val title: String,
    val income: Long,
    val budgeted: Long,
    val spent: Long,
    val toBudget: Long,
    val overspentCount: Int,
)

data class NetWorthData(val budgetName: String, val netWorth: Long, val onBudget: Long, val offBudget: Long, val accountCount: Int)

/** What the extra widgets show, loaded through the session hub like the others; each is null with no budget. */
data class MoreWidgetData(
    val category: CategoryBalanceData?,
    val month: MonthOverviewData,
    val netWorth: NetWorthData,
    val hideAmounts: Boolean,
    val dark: Boolean,
)

object MoreWidgetLoader {
    private val monthTitle = DateTimeFormatter.ofPattern("MMMM yyyy")

    suspend fun load(context: Context, container: AppContainer, today: LocalDate = LocalDate.now()): MoreWidgetData? = withContext(Dispatchers.IO) {
        val settings = container.settings.settings.value
        val id = settings.selectedBudgetId ?: return@withContext null
        val session = container.sessions.acquire(id) { container.library.open(id) ?: error("The selected budget is no longer on this device.") }
        try {
            val monthId = Months.id(today.year, today.monthValue)
            session.read {
                val month = budget.fetchBudgetMonth(monthId)
                val expense = month.categories.filter { !it.isIncome && !it.hidden }
                val pick = WidgetShaping.overspent(month.categories).firstOrNull() ?: expense.minByOrNull { it.spent }
                val accounts = ledger.accounts().filter { !it.closed }
                MoreWidgetData(
                    category = pick?.let { CategoryBalanceData(name, it.id, it.name, it.balance, it.budgeted, it.spent) },
                    month = MonthOverviewData(
                        budgetName = name,
                        title = today.format(monthTitle),
                        income = month.totalIncome,
                        budgeted = month.totalBudgeted,
                        spent = month.totalSpent,
                        toBudget = month.toBudget,
                        overspentCount = WidgetShaping.overspent(month.categories).size,
                    ),
                    netWorth = NetWorthData(
                        budgetName = name,
                        netWorth = accounts.sumOf { it.balance },
                        onBudget = accounts.filter { !it.offBudget }.sumOf { it.balance },
                        offBudget = accounts.filter { it.offBudget }.sumOf { it.balance },
                        accountCount = accounts.size,
                    ),
                    hideAmounts = settings.hideAmounts,
                    dark = WidgetPalette.resolve(context, settings.themeMode).dark,
                )
            }
        } finally {
            container.sessions.release(session)
        }
    }
}

private suspend fun loadMore(context: Context): MoreWidgetData? {
    val container = (context.applicationContext as ActualistApplication).container
    return runCatching { MoreWidgetLoader.load(context, container) }.getOrNull()
}

private fun amount(data: MoreWidgetData, value: Long) = if (data.hideAmounts) "•••" else Money.format(value)

@Composable
private fun Caption(palette: WidgetPalette, text: String) {
    Text(text, style = TextStyle(color = palette.muted, fontSize = 12.sp), maxLines = 1)
}

@Composable
private fun StatRow(palette: WidgetPalette, label: String, value: String) {
    Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = TextStyle(color = palette.muted, fontSize = 13.sp), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        Text(value, style = TextStyle(color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

class CategoryBalanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadMore(context)
        provideContent {
            val palette = WidgetPalette(data?.dark ?: true)
            val category = data?.category
            WidgetFrame(context, palette, if (category != null) "category/${category.categoryId}" else "budget") {
                if (data == null || category == null) {
                    Text("Open Actualist to choose a budget", style = TextStyle(color = palette.text, fontSize = 14.sp))
                } else {
                    Caption(palette, category.name)
                    Spacer(GlanceModifier.height(4.dp))
                    Text(
                        amount(data, category.balance),
                        style = TextStyle(color = if (category.balance < 0) palette.danger else palette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.height(6.dp))
                    StatRow(palette, "Budgeted", amount(data, category.budgeted))
                    StatRow(palette, "Spent", amount(data, category.spent))
                }
            }
        }
    }
}

class MonthOverviewWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadMore(context)
        provideContent {
            val palette = WidgetPalette(data?.dark ?: true)
            WidgetFrame(context, palette, "budget") {
                if (data == null) {
                    Text("Open Actualist to choose a budget", style = TextStyle(color = palette.text, fontSize = 14.sp))
                } else {
                    val month = data.month
                    Caption(palette, month.title)
                    Spacer(GlanceModifier.height(4.dp))
                    StatRow(palette, "To Budget", amount(data, month.toBudget))
                    StatRow(palette, "Income", amount(data, month.income))
                    StatRow(palette, "Budgeted", amount(data, month.budgeted))
                    StatRow(palette, "Spent", amount(data, month.spent))
                    if (month.overspentCount > 0) {
                        Spacer(GlanceModifier.height(4.dp))
                        Text("${month.overspentCount} overspent", style = TextStyle(color = palette.danger, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
}

class NetWorthWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadMore(context)
        provideContent {
            val palette = WidgetPalette(data?.dark ?: true)
            WidgetFrame(context, palette, "reports") {
                if (data == null) {
                    Text("Open Actualist to choose a budget", style = TextStyle(color = palette.text, fontSize = 14.sp))
                } else {
                    val worth = data.netWorth
                    Caption(palette, "Net worth")
                    Spacer(GlanceModifier.height(4.dp))
                    Text(
                        amount(data, worth.netWorth),
                        style = TextStyle(color = if (worth.netWorth < 0) palette.danger else palette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.height(6.dp))
                    StatRow(palette, "On budget", amount(data, worth.onBudget))
                    StatRow(palette, "Off budget", amount(data, worth.offBudget))
                    Caption(palette, "${worth.accountCount} accounts")
                }
            }
        }
    }
}

/** Four buttons that deep-link into the app, Actualist's default quick actions. */
class QuickActionsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as ActualistApplication).container
        val dark = WidgetPalette.resolve(context, container.settings.settings.value.themeMode).dark
        provideContent {
            val palette = WidgetPalette(dark)
            val actions = WidgetQuickAction.defaults
            Column(GlanceModifier.fillMaxSize().background(palette.background).padding(8.dp)) {
                actions.chunked(2).forEachIndexed { index, pair ->
                    if (index > 0) Spacer(GlanceModifier.height(6.dp))
                    Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                        pair.forEachIndexed { column, action ->
                            if (column > 0) Spacer(GlanceModifier.width(6.dp))
                            Text(
                                action.title,
                                style = TextStyle(color = palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                modifier = GlanceModifier.defaultWeight().padding(12.dp)
                                    .background(androidx.compose.ui.graphics.Color(if (dark) 0xFF2E3036 else 0xFFE6E7EC))
                                    .clickable(widgetOpenAction(context, action.route)),
                            )
                        }
                    }
                }
            }
        }
    }
}

class CategoryBalanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CategoryBalanceWidget()
}

class MonthOverviewWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MonthOverviewWidget()
}

class NetWorthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NetWorthWidget()
}

class QuickActionsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickActionsWidget()
}
