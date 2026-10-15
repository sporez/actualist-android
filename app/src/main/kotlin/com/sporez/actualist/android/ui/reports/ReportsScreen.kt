package com.sporez.actualist.android.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.sporez.actualist.android.ui.Loadable
import com.sporez.actualist.android.ui.budget.monthTitle
import com.sporez.actualist.android.ui.components.ErrorBanner
import com.sporez.actualist.android.ui.components.LoadingBox
import com.sporez.actualist.android.ui.components.MessageBox
import com.sporez.actualist.android.ui.components.MoneyText
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.android.ui.components.money
import com.sporez.actualist.android.ui.reloading
import com.sporez.actualist.android.ui.theme.LocalMoneyColors
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.reports.MonthComparisonReport
import com.sporez.actualist.core.reports.ReportCardOrder
import com.sporez.actualist.core.reports.ReportCardReads
import com.sporez.actualist.core.reports.ReportsDashboard
import com.sporez.actualist.core.reports.ThreeMonthAverageReport
import com.sporez.actualist.core.reports.TransactionCalendarMonth
import com.sporez.actualist.core.store.BudgetSession
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

class ReportCards(
    val comparison: MonthComparisonReport,
    val average: ThreeMonthAverageReport,
    val calendar: List<TransactionCalendarMonth>,
)

class ReportsViewModel(session: BudgetSession) : ViewModel() {
    private val today = LocalDate.now()
    private val currentMonth = Months.id(today.year, today.monthValue)
    private val _month = MutableStateFlow(currentMonth)
    val month: kotlinx.coroutines.flow.StateFlow<String> = _month

    /** Past months report through their last day; the current month through today. */
    val dashboard = reloading(session, _month) { month ->
        val end = if (month == currentMonth) today else java.time.YearMonth.parse(month).atEndOfMonth()
        reports.dashboard(month, end.toString())
    }

    /** The "This Month", "3-Month Average" and calendar cards for the same month. */
    val cards = reloading(session, _month) { month ->
        val end = if (month == currentMonth) today else java.time.YearMonth.parse(month).atEndOfMonth()
        val reads = ReportCardReads(database)
        ReportCards(reads.monthComparison(month, end.toString()), reads.threeMonthAverage(month, end.toString()), reads.transactionCalendar(month))
    }

    fun shift(months: Int) {
        val next = Months.shifted(_month.value, months)
        if (next <= currentMonth) _month.value = next
    }

    val isCurrentMonth: Boolean get() = _month.value == currentMonth
}

@Composable
fun ReportsScreen(
    viewModel: ReportsViewModel,
    onExplore: () -> Unit = {},
    /** A card was tapped: its [ReportCardOrder] id, so the Explorer can open preset for that report. */
    onOpenCard: (String) -> Unit = { onExplore() },
    onOpenCategory: (String, String) -> Unit = { _, _ -> },
) {
    val state by viewModel.dashboard.collectAsState()
    val month by viewModel.month.collectAsState()
    val cardsState by viewModel.cards.collectAsState()
    val cards = (cardsState as? Loadable.Ready)?.value
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val orderStore = remember { ReportOrderStore(context) }
    var order by remember { mutableStateOf(orderStore.load()) }
    var editingOrder by remember { mutableStateOf(false) }
    if (editingOrder) {
        ReportOrderDialog(
            order = order,
            onMove = { id, delta ->
                order = ReportCardOrder.move(order, id, delta)
                orderStore.save(order)
            },
            onReset = {
                orderStore.reset()
                order = orderStore.load()
            },
            onDismiss = { editingOrder = false },
        )
    }
    Box(Modifier.fillMaxSize().testTag("reports-screen")) {
        when (val current = state) {
            Loadable.Loading -> LoadingBox()
            is Loadable.Failed -> ErrorBanner(current.message, Modifier.padding(16.dp))
            is Loadable.Ready -> {
                val dashboard = current.value
                if (!dashboard.hasData) {
                    MessageBox("No report data yet", "Reports fill in once the budget has transactions.")
                } else {
                    LazyColumn(Modifier.testTag("reports-list"), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                androidx.compose.material3.TextButton(onClick = { viewModel.shift(-1) }, modifier = Modifier.testTag("reports-previous")) { Text("‹ Previous") }
                                Text(monthTitle(month), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                androidx.compose.material3.TextButton(onClick = { viewModel.shift(1) }, enabled = !viewModel.isCurrentMonth, modifier = Modifier.testTag("reports-next")) { Text("Next ›") }
                            }
                        }
                        item {
                            androidx.compose.material3.OutlinedButton(onClick = onExplore, modifier = Modifier.fillMaxWidth().testTag("open-explorer")) {
                                Text("Explore: any range, accounts and categories")
                            }
                        }
                        item {
                            androidx.compose.material3.TextButton(onClick = { editingOrder = true }, modifier = Modifier.testTag("report-order-edit")) {
                                Text("Edit card order")
                            }
                        }
                        for (id in order) {
                            when (id) {
                                "net-worth" -> item(key = id) { NetWorthCard(dashboard) { onOpenCard(id) } }
                                "cash-flow" -> item(key = id) { CashFlowCard(dashboard) { onOpenCard(id) } }
                                "month-comparison" -> if (cards != null) item(key = id) { MonthComparisonCard(cards.comparison) { onOpenCard(id) } }
                                "spending" -> item(key = id) { SpendingCard(dashboard, onOpenCategory) { onOpenCard(id) } }
                                "budget-comparison" -> item(key = id) { BudgetComparisonCard(dashboard, onOpenCategory) { onOpenCard(id) } }
                                "three-month-average" -> if (cards != null) item(key = id) { ThreeMonthAverageCard(cards.average) { onOpenCard(id) } }
                                "transaction-calendar" -> if (cards != null) item(key = id) { TransactionCalendarCard(cards.calendar) { onOpenCard(id) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val cardTitles = mapOf(
    "net-worth" to "Net Worth",
    "cash-flow" to "Cash Flow",
    "month-comparison" to "This Month",
    "spending" to "Spending by Category",
    "budget-comparison" to "Budget vs Spent",
    "three-month-average" to "3-Month Average",
    "transaction-calendar" to "Transaction Calendar",
)

/** Move cards up or down; the order is saved as it changes. "Reset Report Order" restores the default. */
@Composable
private fun ReportOrderDialog(order: List<String>, onMove: (String, Int) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report order") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).testTag("report-order-list")) {
                order.forEachIndexed { index, id ->
                    Row(Modifier.fillMaxWidth().testTag("report-order-row-$id"), verticalAlignment = Alignment.CenterVertically) {
                        Text(cardTitles[id] ?: id, Modifier.weight(1f))
                        androidx.compose.material3.TextButton(onClick = { onMove(id, -1) }, enabled = index > 0, modifier = Modifier.testTag("report-order-up-$id")) { Text("Up") }
                        androidx.compose.material3.TextButton(onClick = { onMove(id, 1) }, enabled = index < order.lastIndex, modifier = Modifier.testTag("report-order-down-$id")) { Text("Down") }
                    }
                }
                androidx.compose.material3.TextButton(onClick = onReset, modifier = Modifier.testTag("report-order-reset")) { Text("Reset Report Order") }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss, modifier = Modifier.testTag("report-order-done")) { Text("Done") } },
    )
}

@Composable
private fun CardTitle(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NetWorthCard(dashboard: ReportsDashboard, onOpen: () -> Unit) {
    val report = dashboard.netWorth
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    SectionCard(Modifier.testTag("report-net-worth").clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle("Net Worth", "${monthTitle(report.points.first().month)} – ${monthTitle(report.points.last().month)}")
            Row(verticalAlignment = Alignment.Bottom) {
                MoneyText(report.balance, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                MoneyText(report.change, colored = true)
            }
            val values = report.points.map { it.value.toFloat() }
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val min = values.minOrNull() ?: 0f
                val max = values.maxOrNull() ?: 0f
                val span = (max - min).takeIf { it > 0f } ?: 1f
                val step = if (values.size > 1) size.width / (values.size - 1) else 0f
                for (i in 0..2) {
                    val y = size.height * i / 2f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = index * step
                    val y = size.height - (value - min) / span * size.height * 0.9f - size.height * 0.05f
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(x, y))
                }
                drawPath(path, lineColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            }
            Row {
                report.points.forEach { Text(monthTitle(it.month).take(3), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun CashFlowCard(dashboard: ReportsDashboard, onOpen: () -> Unit) {
    val flow = dashboard.cashFlow
    val colors = LocalMoneyColors.current
    SectionCard(Modifier.testTag("report-cash-flow").clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle("Cash Flow", "${monthTitle(flow.month)} so far · transfers excluded")
            Row {
                Column(Modifier.weight(1f)) {
                    Text("Income", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyText(flow.income, modifier = Modifier.testTag("cash-flow-income"))
                }
                Column(Modifier.weight(1f)) {
                    Text("Expenses", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyText(flow.expenses, modifier = Modifier.testTag("cash-flow-expenses"))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("Net", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyText(flow.net, colored = true)
                }
            }
            val history = dashboard.cashFlowHistory
            val maxValue = history.maxOfOrNull { maxOf(it.income, it.expenses) }?.takeIf { it > 0 } ?: 1L
            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                val slot = size.width / history.size
                val barWidth = slot * 0.32f
                history.forEachIndexed { index, month ->
                    val x = index * slot + slot * 0.15f
                    val incomeHeight = size.height * month.income / maxValue
                    val expenseHeight = size.height * month.expenses / maxValue
                    drawRoundRect(colors.positiveContainer, Offset(x, size.height - incomeHeight), Size(barWidth, incomeHeight), androidx.compose.ui.geometry.CornerRadius(6f, 6f))
                    drawRoundRect(colors.negativeContainer, Offset(x + barWidth + 4f, size.height - expenseHeight), Size(barWidth, expenseHeight), androidx.compose.ui.geometry.CornerRadius(6f, 6f))
                }
            }
            Row {
                history.forEach { Text(monthTitle(it.month).take(3), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (flow.uncategorized != 0L) {
                Text("Uncategorized activity: ${money(flow.uncategorized)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SpendingCard(dashboard: ReportsDashboard, onOpenCategory: (String, String) -> Unit, onOpen: () -> Unit) {
    val spending = dashboard.spending.filter { it.spent > 0 }
    val total = spending.sumOf { it.spent }.takeIf { it > 0 } ?: 1L
    SectionCard(Modifier.testTag("report-spending").clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle("Spending by Category", "${monthTitle(dashboard.anchorMonth)} · budgeted ${money(dashboard.budgetedExpenses)}")
            for (item in spending.take(10)) {
                Column(
                    Modifier.clickable { onOpenCategory(item.categoryId, item.name) }.testTag("report-category-${item.categoryId}"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row {
                        Text(item.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        MoneyText(item.spent)
                    }
                    LinearProgressIndicator(
                        progress = { item.spent.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                }
            }
            if (spending.isEmpty()) Text("No spending yet this month.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


/** Budgeted against spent per category for the month, largest overspending first. */
@Composable
private fun BudgetComparisonCard(dashboard: ReportsDashboard, onOpenCategory: (String, String) -> Unit, onOpen: () -> Unit) {
    val rows = dashboard.spending.filter { it.budgeted != 0L || it.spent != 0L }.sortedBy { it.budgeted - it.spent }
    SectionCard(Modifier.testTag("report-budget-comparison").clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle("Budget vs Spent", "${monthTitle(dashboard.anchorMonth)} · ${money(rows.sumOf { it.spent })} of ${money(rows.sumOf { it.budgeted })}")
            for (item in rows.take(12)) {
                val remaining = item.budgeted - item.spent
                Row(Modifier.fillMaxWidth().clickable { onOpenCategory(item.categoryId, item.name) }.padding(vertical = 2.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${money(item.spent)} of ${money(item.budgeted)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MoneyText(remaining, colored = true)
                }
            }
            if (rows.isEmpty()) Text("Nothing budgeted or spent this month.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Spending above the comparison is overspending, so it reads as negative. */
@Composable
private fun VarianceHeadline(variance: Long, tag: String) {
    val colors = LocalMoneyColors.current
    val color = when {
        variance > 0 -> colors.negative
        variance < 0 -> colors.positive
        else -> MaterialTheme.colorScheme.onSurface
    }
    val text = (if (variance > 0) "+" else if (variance < 0) "-" else "") + money(kotlin.math.abs(variance))
    Text(text, Modifier.testTag(tag), style = MaterialTheme.typography.headlineSmall, color = color)
}

@Composable
private fun ComparisonChart(points: List<com.sporez.actualist.core.reports.DailyComparisonPoint>) {
    val currentColor = MaterialTheme.colorScheme.primary
    val referenceColor = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val maxValue = points.maxOfOrNull { maxOf(it.current ?: 0L, it.comparison) }?.takeIf { it > 0 } ?: 1L
        val step = if (points.size > 1) size.width / (points.size - 1) else 0f
        fun y(value: Long) = size.height - value.toFloat() / maxValue * size.height * 0.95f - size.height * 0.02f
        val reference = Path()
        val current = Path()
        points.forEachIndexed { index, point ->
            val x = index * step
            if (index == 0) reference.moveTo(x, y(point.comparison)) else reference.lineTo(x, y(point.comparison))
            val value = point.current
            if (value != null) {
                if (current.isEmpty) current.moveTo(x, y(value)) else current.lineTo(x, y(value))
            }
        }
        drawPath(reference, referenceColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawPath(current, currentColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun MonthComparisonCard(report: MonthComparisonReport, onOpen: () -> Unit) {
    SectionCard(Modifier.testTag("report-month-comparison").clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle("This Month", "Compare ${monthTitle(report.currentMonth)} to ${monthTitle(report.comparisonMonth)}")
            VarianceHeadline(report.variance, "month-comparison-variance")
            ComparisonChart(report.points)
        }
    }
}

@Composable
private fun ThreeMonthAverageCard(report: ThreeMonthAverageReport, onOpen: () -> Unit) {
    SectionCard(Modifier.testTag("report-three-month-average").clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle("3-Month Average", "Compare ${monthTitle(report.month)} to the last 3 months")
            VarianceHeadline(report.variance, "three-month-variance")
            ComparisonChart(report.points)
        }
    }
}

@Composable
private fun TransactionCalendarCard(months: List<TransactionCalendarMonth>, onOpen: () -> Unit) {
    val colors = LocalMoneyColors.current
    val maximum = months.maxOfOrNull { month -> month.days.maxOfOrNull { maxOf(it.income, it.expenses) } ?: 0L }?.takeIf { it > 0 } ?: 1L
    fun intensity(amount: Long): Float = if (amount == 0L) 0f else (amount.toFloat() / maximum).coerceIn(0.22f, 1f)
    val weekdays = listOf("S", "M", "T", "W", "T", "F", "S")
    SectionCard(Modifier.testTag("report-transaction-calendar").clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle("Transaction Calendar", "${monthTitle(months.first().month)} – ${monthTitle(months.last().month)}")
            for (month in months) {
                Column(Modifier.testTag("calendar-month-${month.month}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(monthTitle(month.month), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text("↑ ${money(month.income)}", Modifier.testTag("calendar-income-${month.month}"), style = MaterialTheme.typography.labelMedium, color = colors.positive)
                        Spacer(Modifier.width(10.dp))
                        Text("↓ ${money(month.expenses)}", Modifier.testTag("calendar-expenses-${month.month}"), style = MaterialTheme.typography.labelMedium, color = colors.negative)
                    }
                    Row {
                        weekdays.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                    }
                    val cells: List<com.sporez.actualist.core.reports.TransactionCalendarDay?> =
                        List(month.leadingBlankCount) { null } + month.days
                    for (week in cells.chunked(7)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (index in 0 until 7) {
                                val day = week.getOrNull(index)
                                if (day == null) {
                                    Spacer(Modifier.weight(1f).height(34.dp))
                                } else {
                                    Box(
                                        Modifier.weight(1f).height(34.dp)
                                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp))
                                            .testTag("calendar-day-${day.dayId}"),
                                    ) {
                                        Text(day.day.toString(), Modifier.align(Alignment.TopCenter).padding(top = 3.dp), style = MaterialTheme.typography.labelSmall)
                                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                            Box(Modifier.fillMaxWidth().weight(1f).background(colors.positive.copy(alpha = intensity(day.income))))
                                            Box(Modifier.fillMaxWidth().weight(1f).background(colors.negative.copy(alpha = intensity(day.expenses))))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
