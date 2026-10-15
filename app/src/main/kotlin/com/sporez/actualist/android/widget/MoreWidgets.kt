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
import androidx.glance.appwidget.SizeMode
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
import com.sporez.actualist.android.data.NamedTheme
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.widgets.Sparkline
import com.sporez.actualist.core.widgets.WidgetChoices
import com.sporez.actualist.core.widgets.WidgetShaping
import com.sporez.actualist.core.widgets.WidgetSizeClass
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

        /** The four a widget shows: its chosen ids (unknown ones dropped) topped up with the defaults. */
        fun chosen(ids: List<String>?): List<WidgetQuickAction> {
            val byId = entries.associateBy { it.id }
            return WidgetChoices.quickActions(ids, byId.keys, defaults.map { it.id }).mapNotNull { byId[it] }
        }
    }
}

/** One category a Category Balances widget lists. */
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

/** [trend] is the last months' net worth, oldest first. */
data class NetWorthData(val budgetName: String, val netWorth: Long, val onBudget: Long, val offBudget: Long, val accountCount: Int, val trend: List<Long> = emptyList())

/** What the extra widgets show, loaded through the session hub like the others; each is null with no budget. */
data class MoreWidgetData(
    /** The chosen categories in order, or Actualist's default one: the most overspent, else the one with the most spent. */
    val categories: List<CategoryBalanceData>,
    val month: MonthOverviewData,
    val netWorth: NetWorthData,
    val hideAmounts: Boolean,
    val dark: Boolean,
    val named: NamedTheme? = null,
) {
    val category: CategoryBalanceData? get() = categories.firstOrNull()
}

object MoreWidgetLoader {
    private val monthTitle = DateTimeFormatter.ofPattern("MMMM yyyy")

    suspend fun load(context: Context, container: AppContainer, today: LocalDate = LocalDate.now(), categoryIds: List<String>? = null): MoreWidgetData? = withContext(Dispatchers.IO) {
        val settings = container.settings.settings.value
        val id = settings.selectedBudgetId ?: return@withContext null
        val session = container.sessions.acquire(id) { container.library.open(id) ?: error("The selected budget is no longer on this device.") }
        try {
            val monthId = Months.id(today.year, today.monthValue)
            session.read {
                val month = budget.fetchBudgetMonth(monthId)
                val expense = month.categories.filter { !it.isIncome && !it.hidden }
                val pick = WidgetShaping.overspent(month.categories).firstOrNull() ?: expense.minByOrNull { it.spent }
                val chosen = WidgetChoices.pick(categoryIds, expense, WidgetChoices.CATEGORY_LIMIT, listOfNotNull(pick)) { it.id }
                val accounts = ledger.accounts().filter { !it.closed }
                val trend = runCatching { reports.dashboard(monthId, today.toString()).netWorth.points.map { it.value } }.getOrDefault(emptyList())
                MoreWidgetData(
                    categories = chosen.map { CategoryBalanceData(name, it.id, it.name, it.balance, it.budgeted, it.spent) },
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
                        trend = trend,
                    ),
                    hideAmounts = settings.hideAmounts,
                    dark = WidgetPalette.of(context, settings).dark,
                    named = settings.namedTheme,
                )
            }
        } finally {
            container.sessions.release(session)
        }
    }
}

private suspend fun loadMore(context: Context, categoryIds: List<String>? = null): MoreWidgetData? {
    val container = (context.applicationContext as ActualistApplication).container
    return runCatching { MoreWidgetLoader.load(context, container, categoryIds = categoryIds) }.getOrNull()
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

private fun paletteOf(data: MoreWidgetData?) = WidgetPalette(data?.dark ?: true, data?.named)

class CategoryBalanceWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = WidgetResponsiveSizes

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadMore(context, chosenIds(context, id))
        provideContent {
            val palette = paletteOf(data)
            val family = currentSizeClass()
            val categories = data?.categories.orEmpty()
            WidgetFrame(context, palette, if (categories.size == 1) "category/${categories.first().categoryId}" else "budget") {
                if (data == null || categories.isEmpty()) {
                    Text("Open Actualist to choose a budget", style = TextStyle(color = palette.text, fontSize = 14.sp))
                } else if (family == WidgetSizeClass.SMALL || categories.size == 1) {
                    val category = categories.first()
                    Caption(palette, category.name)
                    Spacer(GlanceModifier.height(4.dp))
                    Text(
                        amount(data, category.balance),
                        style = TextStyle(color = if (category.balance < 0) palette.danger else palette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    if (family != WidgetSizeClass.SMALL) {
                        Spacer(GlanceModifier.height(6.dp))
                        StatRow(palette, "Budgeted", amount(data, category.budgeted))
                        StatRow(palette, "Spent", amount(data, category.spent))
                    }
                } else {
                    Caption(palette, "Category balances")
                    Spacer(GlanceModifier.height(4.dp))
                    categories.take(family.categoryRows).forEach { category ->
                        Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp).clickable(widgetOpenAction(context, "category/${category.categoryId}"))) {
                            Text(category.name, style = TextStyle(color = palette.text, fontSize = 13.sp), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                            Text(
                                amount(data, category.balance),
                                style = TextStyle(color = if (category.balance < 0) palette.danger else palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

class MonthOverviewWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = WidgetResponsiveSizes

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadMore(context)
        provideContent {
            val palette = paletteOf(data)
            val family = currentSizeClass()
            WidgetFrame(context, palette, "budget") {
                if (data == null) {
                    Text("Open Actualist to choose a budget", style = TextStyle(color = palette.text, fontSize = 14.sp))
                } else {
                    val month = data.month
                    Caption(palette, month.title)
                    Spacer(GlanceModifier.height(4.dp))
                    if (family == WidgetSizeClass.SMALL) {
                        Caption(palette, "To Budget")
                        Text(
                            amount(data, month.toBudget),
                            style = TextStyle(color = if (month.toBudget < 0) palette.danger else palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1,
                        )
                    } else {
                        StatRow(palette, "To Budget", amount(data, month.toBudget))
                        StatRow(palette, "Income", amount(data, month.income))
                        StatRow(palette, "Budgeted", amount(data, month.budgeted))
                        StatRow(palette, "Spent", amount(data, month.spent))
                    }
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
    override val sizeMode: SizeMode = WidgetResponsiveSizes

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadMore(context)
        provideContent {
            val palette = paletteOf(data)
            val family = currentSizeClass()
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
                    if (worth.trend.size > 1 && !data.hideAmounts) {
                        Spacer(GlanceModifier.height(4.dp))
                        Text(Sparkline.render(worth.trend), style = TextStyle(color = palette.accent, fontSize = if (family == WidgetSizeClass.SMALL) 16.sp else 22.sp), maxLines = 1)
                    }
                    if (family != WidgetSizeClass.SMALL) {
                        Spacer(GlanceModifier.height(6.dp))
                        StatRow(palette, "On budget", amount(data, worth.onBudget))
                        StatRow(palette, "Off budget", amount(data, worth.offBudget))
                        Caption(palette, "${worth.accountCount} accounts")
                    }
                }
            }
        }
    }
}

/** Buttons that deep-link into the app: the four the widget was configured with, Actualist's defaults otherwise. */
class QuickActionsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = WidgetResponsiveSizes

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as ActualistApplication).container
        val palette = WidgetPalette.of(context, container.settings.settings.value)
        val chosen = WidgetQuickAction.chosen(chosenIds(context, id))
        provideContent {
            val actions = if (currentSizeClass() == WidgetSizeClass.SMALL) chosen.take(2) else chosen
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
                                    .background(palette.control)
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
