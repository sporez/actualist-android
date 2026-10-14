package com.sporez.actualist.android.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.sporez.actualist.core.reports.ReportsDashboard
import com.sporez.actualist.core.store.BudgetSession
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

class ReportsViewModel(session: BudgetSession) : ViewModel() {
    private val today = LocalDate.now()
    val dashboard = reloading(session, MutableStateFlow(today)) { day ->
        reports.dashboard(Months.id(day.year, day.monthValue), day.toString())
    }
}

@Composable
fun ReportsScreen(viewModel: ReportsViewModel) {
    val state by viewModel.dashboard.collectAsState()
    Box(Modifier.fillMaxSize().testTag("reports-screen")) {
        when (val current = state) {
            Loadable.Loading -> LoadingBox()
            is Loadable.Failed -> ErrorBanner(current.message, Modifier.padding(16.dp))
            is Loadable.Ready -> {
                val dashboard = current.value
                if (!dashboard.hasData) {
                    MessageBox("No report data yet", "Reports fill in once the budget has transactions.")
                } else {
                    LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { NetWorthCard(dashboard) }
                        item { CashFlowCard(dashboard) }
                        item { SpendingCard(dashboard) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardTitle(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NetWorthCard(dashboard: ReportsDashboard) {
    val report = dashboard.netWorth
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    SectionCard(Modifier.testTag("report-net-worth")) {
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
private fun CashFlowCard(dashboard: ReportsDashboard) {
    val flow = dashboard.cashFlow
    val colors = LocalMoneyColors.current
    SectionCard(Modifier.testTag("report-cash-flow")) {
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
private fun SpendingCard(dashboard: ReportsDashboard) {
    val spending = dashboard.spending.filter { it.spent > 0 }
    val total = spending.sumOf { it.spent }.takeIf { it > 0 } ?: 1L
    SectionCard(Modifier.testTag("report-spending")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle("Spending by Category", "${monthTitle(dashboard.anchorMonth)} · budgeted ${money(dashboard.budgetedExpenses)}")
            for (item in spending.take(10)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
