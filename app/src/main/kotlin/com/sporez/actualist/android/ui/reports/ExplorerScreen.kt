@file:OptIn(ExperimentalMaterial3Api::class)

package com.sporez.actualist.android.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.sporez.actualist.android.ui.Loadable
import com.sporez.actualist.android.ui.components.ErrorBanner
import com.sporez.actualist.android.ui.components.LoadingBox
import com.sporez.actualist.android.ui.components.MoneyText
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.android.ui.reloading
import com.sporez.actualist.android.ui.theme.LocalMoneyColors
import com.sporez.actualist.core.reports.ExplorerFilter
import com.sporez.actualist.core.reports.ExplorerPaging
import com.sporez.actualist.core.reports.ExplorerPreset
import com.sporez.actualist.core.reports.ExplorerResult
import com.sporez.actualist.core.reports.RangePreset
import com.sporez.actualist.core.reports.ReportExplorer
import com.sporez.actualist.core.reports.ReportInterval
import com.sporez.actualist.core.reports.ReportMetric
import com.sporez.actualist.core.reports.ReportSeries
import com.sporez.actualist.core.reports.ReportSeriesException
import com.sporez.actualist.core.reports.SeriesCatalog
import com.sporez.actualist.core.reports.SeriesFilters
import com.sporez.actualist.core.reports.SeriesPoint
import com.sporez.actualist.core.reports.SeriesQuery
import com.sporez.actualist.core.reports.SeriesSnapshot
import com.sporez.actualist.core.store.BudgetSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

data class ExplorerState(
    /** Null when the query is invalid; [error] says why. */
    val snapshot: SeriesSnapshot?,
    val error: String?,
    val catalog: SeriesCatalog,
    /** Matching transactions for the same range and filters, grouped by category. */
    val result: ExplorerResult,
)

/**
 * Actualist's Report Explorer: one of five reports over a range and interval with the filter
 * sheet, plus the matching transactions below the chart.
 */
class ExplorerViewModel(
    session: BudgetSession,
    private val today: LocalDate = LocalDate.now(),
    /** The report, range and interval a tapped Reports card opens with; null opens Cash Flow for the month to date. */
    preset: ExplorerPreset? = null,
) : ViewModel() {
    private val initialPreset = preset?.range ?: RangePreset.MONTH_TO_DATE
    private val initialRange = initialPreset.range(today)!!
    private val _query = MutableStateFlow(
        SeriesQuery(preset?.metric ?: ReportMetric.CASH_FLOW, initialRange.first, initialRange.second, preset?.interval ?: ReportInterval.MONTH),
    )
    val query: StateFlow<SeriesQuery> = _query
    private val _preset = MutableStateFlow(initialPreset)
    val preset: StateFlow<RangePreset> = _preset

    val state = reloading(session, _query) { q ->
        val catalog = ReportSeries(database).catalog()
        val snapshot = try {
            ReportSeries(database).snapshot(q)
        } catch (failure: ReportSeriesException) {
            return@reloading ExplorerState(null, failure.message, catalog, emptyExplorerResult())
        }
        val result = ReportExplorer(database).explore(
            ExplorerFilter(
                start = q.start,
                end = q.end,
                accountIds = q.filters.accounts?.takeIf { it.isNotEmpty() },
                categoryIds = q.filters.categories?.takeIf { it.isNotEmpty() },
            ),
        )
        ExplorerState(snapshot, null, catalog, result)
    }

    /** Actualist opens each report with its own default range and interval. */
    fun selectMetric(metric: ReportMetric) {
        val current = _query.value
        if (current.metric == metric) return
        val preset = if (metric == ReportMetric.NET_WORTH) RangePreset.SIX_MONTHS else RangePreset.MONTH_TO_DATE
        val interval = if (metric == ReportMetric.NET_WORTH || metric == ReportMetric.CASH_FLOW) ReportInterval.MONTH else ReportInterval.DAY
        val range = preset.range(today)!!
        _preset.value = preset
        _query.value = SeriesQuery(metric, range.first, range.second, interval, current.filters.normalized(metric))
    }

    fun selectInterval(interval: ReportInterval) {
        _query.value = _query.value.copy(interval = interval)
    }

    fun selectPreset(preset: RangePreset) {
        val current = _query.value
        if (current.metric == ReportMetric.SPENDING_AVERAGE) return
        _preset.value = preset
        val range = preset.range(today) ?: return
        _query.value = current.copy(start = range.first, end = range.second)
    }

    fun selectCustomRange(start: LocalDate, end: LocalDate) {
        if (_query.value.metric == ReportMetric.SPENDING_AVERAGE) return
        _preset.value = RangePreset.CUSTOM
        _query.value = _query.value.copy(start = start, end = end)
    }

    val canAdvanceAverageMonth: Boolean get() = YearMonth.from(_query.value.start).isBefore(YearMonth.from(today))

    /** Spending Average compares one month at a time, never a future one. */
    fun shiftAverageMonth(delta: Int) {
        val current = _query.value
        val month = YearMonth.from(current.start).plusMonths(delta.toLong())
        if (month.isAfter(YearMonth.from(today))) return
        val end = if (month == YearMonth.from(today)) today else month.atEndOfMonth()
        _query.value = current.copy(start = month.atDay(1), end = end)
    }

    fun applyFilters(filters: SeriesFilters) {
        val current = _query.value
        _query.value = current.copy(filters = filters.normalized(current.metric))
    }
}

private fun emptyExplorerResult() = ExplorerResult(0L, 0L, emptyList(), emptyList())

private class ChartSeries(val color: Color, val value: (SeriesPoint) -> Long)

private fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toPickerDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
fun ExplorerScreen(
    viewModel: ExplorerViewModel,
    today: LocalDate = LocalDate.now(),
    onViewTransactions: (String) -> Unit = {},
    onOpenTransaction: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val query by viewModel.query.collectAsState()
    val preset by viewModel.preset.collectAsState()
    var focusCategory by remember { mutableStateOf<String?>(null) }
    var filtering by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf<String?>(null) }
    // Rows shown so far; a new query or category focus starts again at the first page.
    var shown by remember(query, focusCategory) { mutableStateOf(ExplorerPaging.PAGE_SIZE) }
    when (val current = state) {
        Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBanner(current.message, Modifier.padding(16.dp))
        is Loadable.Ready -> {
            val explorer = current.value
            val names = (explorer.catalog.accounts.map { it.id to it.name } + explorer.catalog.categories.map { it.id to it.name }).toMap()
            val rows = explorer.result.rows.filter { focusCategory == null || it.categoryId == focusCategory }
            LazyColumn(Modifier.fillMaxSize().testTag("explorer-screen"), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(ReportMetric.entries.toList(), key = { it.name }) { metric ->
                            FilterChip(
                                selected = query.metric == metric,
                                onClick = { viewModel.selectMetric(metric) },
                                label = { Text(metric.title) },
                                modifier = Modifier.testTag("explorer-metric-${metric.name}"),
                            )
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (interval in ReportInterval.entries) {
                            FilterChip(
                                selected = query.interval == interval,
                                onClick = { viewModel.selectInterval(interval) },
                                label = { Text(interval.title) },
                                modifier = Modifier.testTag("explorer-interval-${interval.name}"),
                            )
                        }
                        Text("", Modifier.weight(1f))
                        OutlinedButton(onClick = { filtering = true }, modifier = Modifier.testTag("explorer-filters")) {
                            val count = query.filters.activeCount(query.metric)
                            Text(if (count > 0) "Filters ($count)" else "Filters")
                        }
                    }
                }
                item {
                    if (query.metric == ReportMetric.SPENDING_AVERAGE) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { viewModel.shiftAverageMonth(-1) }, modifier = Modifier.testTag("explorer-average-previous")) { Text("‹ Previous") }
                            Text(
                                com.sporez.actualist.android.ui.budget.monthTitle(YearMonth.from(query.start).toString()),
                                Modifier.weight(1f).testTag("explorer-average-month"),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            TextButton(onClick = { viewModel.shiftAverageMonth(1) }, enabled = viewModel.canAdvanceAverageMonth, modifier = Modifier.testTag("explorer-average-next")) { Text("Next ›") }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LazyRow(Modifier.testTag("explorer-ranges"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(RangePreset.entries.toList(), key = { it.name }) { option ->
                                    FilterChip(
                                        selected = preset == option,
                                        onClick = { viewModel.selectPreset(option) },
                                        label = { Text(option.title) },
                                        modifier = Modifier.testTag("explorer-range-${option.title}"),
                                    )
                                }
                            }
                            if (preset == RangePreset.CUSTOM) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { pickingDate = "start" }, modifier = Modifier.testTag("explorer-custom-start")) { Text("From ${query.start}") }
                                    OutlinedButton(onClick = { pickingDate = "end" }, modifier = Modifier.testTag("explorer-custom-end")) { Text("To ${query.end}") }
                                }
                            }
                        }
                    }
                }
                item {
                    val snapshot = explorer.snapshot
                    if (snapshot == null) {
                        ErrorBanner(explorer.error ?: "This report could not be loaded.", Modifier.testTag("explorer-error"))
                    } else {
                        SeriesCard(snapshot)
                    }
                }
                // An invalid range or filter combination shows only its message, as Actualist does.
                if (explorer.snapshot != null) {
                item {
                    SectionCard(Modifier.testTag("explorer-totals")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${query.start} – ${query.end}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TotalRow("Income", explorer.result.income)
                            TotalRow("Expenses", explorer.result.expenses)
                            HorizontalDivider()
                            TotalRow("Net", explorer.result.net)
                        }
                    }
                }
                item {
                    // As Actualist: the transactions behind these totals, grouped and labelled.
                    ViewContributingTransactionsButton(onClick = {
                        onViewTransactions(drilldownRoute(query.start, query.end, query.filters.accounts, query.filters.categories, "Transactions"))
                    })
                }
                item {
                    SectionCard {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            for (total in explorer.result.byCategory.take(25)) {
                                val selected = focusCategory == total.categoryId && focusCategory != null
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { focusCategory = if (selected) null else total.categoryId }
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                        .testTag("explorer-category-${total.categoryId ?: "none"}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(total.categoryId?.let { names[it] } ?: "Uncategorized", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        Text("${total.count} transactions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    MoneyText(total.total, colored = true)
                                }
                            }
                            if (explorer.result.byCategory.isEmpty()) Text("Nothing matches these filters.", Modifier.padding(16.dp))
                        }
                    }
                }
                item {
                    Text(
                        if (focusCategory != null) "Transactions in ${names[focusCategory] ?: "this category"}" else "Transactions",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(rows.take(shown), key = { it.id ?: it.hashCode().toString() }) { row ->
                    val target = row.parentId ?: row.id
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = target != null) { target?.let(onOpenTransaction) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("explorer-row"),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(row.payeeName ?: row.importedPayee ?: "No payee", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${row.date} · ${names[row.accountId] ?: row.accountId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        MoneyText(row.amount ?: 0L, colored = true)
                    }
                }
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Showing ${minOf(shown, rows.size)} of ${rows.size}",
                            Modifier.testTag("explorer-rows-count"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (ExplorerPaging.hasMore(shown, rows.size)) {
                            OutlinedButton(onClick = { shown = ExplorerPaging.next(shown, rows.size) }, modifier = Modifier.testTag("explorer-load-more")) {
                                Text("Show more")
                            }
                        }
                    }
                }
                }
            }
            if (filtering) {
                FilterSheet(
                    metric = query.metric,
                    catalog = explorer.catalog,
                    initial = query.filters,
                    onApply = { viewModel.applyFilters(it); filtering = false },
                    onDismiss = { filtering = false },
                )
            }
            pickingDate?.let { which ->
                val initial = if (which == "start") query.start else query.end
                val limit = if (which == "start") query.end else query.start
                val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial.toPickerMillis())
                DatePickerDialog(
                    onDismissRequest = { pickingDate = null },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pickerState.selectedDateMillis?.toPickerDate()?.let { picked ->
                                    if (which == "start") viewModel.selectCustomRange(picked, if (picked.isAfter(limit)) picked else limit)
                                    else viewModel.selectCustomRange(if (picked.isBefore(limit)) picked else limit, picked)
                                }
                                pickingDate = null
                            },
                            modifier = Modifier.testTag("explorer-date-ok"),
                        ) { Text("OK") }
                    },
                    dismissButton = { TextButton(onClick = { pickingDate = null }) { Text("Cancel") } },
                ) { DatePicker(state = pickerState) }
            }
        }
    }
}

@Composable
private fun TotalRow(label: String, value: Long) {
    Row {
        Text(label, Modifier.weight(1f))
        MoneyText(value, colored = true)
    }
}

@Composable
private fun SeriesCard(snapshot: SeriesSnapshot) {
    val metric = snapshot.query.metric
    val totals = snapshot.totals
    SectionCard(Modifier.testTag("explorer-chart-${metric.name}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(metric.title, style = MaterialTheme.typography.titleMedium)
            Text("${snapshot.query.start} – ${snapshot.query.end} · ${snapshot.query.interval.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val (label, primary) = when (metric) {
                ReportMetric.NET_WORTH -> "Ending balance" to totals.endingBalance
                ReportMetric.CASH_FLOW -> "Net cash flow" to totals.net
                ReportMetric.SPENDING -> "Total spending" to totals.expenses
                ReportMetric.BUDGET_OVERVIEW -> "Spending" to totals.expenses
                ReportMetric.SPENDING_AVERAGE -> "3-month average spending" to totals.averageSpending
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyText(primary, style = MaterialTheme.typography.headlineSmall, colored = metric == ReportMetric.CASH_FLOW, modifier = Modifier.testTag("explorer-primary-total"))
            when (metric) {
                ReportMetric.NET_WORTH -> SecondaryRow("Change" to totals.balanceChange)
                ReportMetric.CASH_FLOW -> SecondaryRow("Income" to totals.income, "Expenses" to totals.expenses)
                ReportMetric.SPENDING -> Unit
                ReportMetric.BUDGET_OVERVIEW -> SecondaryRow("Budgeted" to totals.budgeted)
                ReportMetric.SPENDING_AVERAGE -> SecondaryRow("This range" to totals.expenses)
            }
            if (!snapshot.hasData) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag("explorer-empty"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("No Activity in This Range", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Choose another date range to explore this report.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                SeriesChart(snapshot)
                Row {
                    val first = snapshot.points.firstOrNull()
                    val last = snapshot.points.lastOrNull()
                    Text(first?.period?.start?.toString().orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(last?.period?.end?.toString().orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SecondaryRow(vararg values: Pair<String, Long>) {
    Row {
        for ((label, value) in values) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MoneyText(value, colored = true)
            }
        }
    }
}

@Composable
private fun SeriesChart(snapshot: SeriesSnapshot) {
    val colors = LocalMoneyColors.current
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.outline
    val grid = MaterialTheme.colorScheme.outlineVariant
    val points = snapshot.points
    Canvas(Modifier.fillMaxWidth().height(140.dp).testTag("explorer-canvas")) {
        when (snapshot.query.metric) {
            ReportMetric.NET_WORTH -> drawSeriesLines(points, grid, listOf(ChartSeries(primary) { it.endingBalance }), includeZero = false)
            ReportMetric.BUDGET_OVERVIEW -> drawSeriesLines(points, grid, listOf(ChartSeries(secondary) { it.budgeted }, ChartSeries(primary) { it.expenses }), includeZero = true)
            ReportMetric.SPENDING_AVERAGE -> drawSeriesLines(points, grid, listOf(ChartSeries(secondary) { it.comparison }, ChartSeries(primary) { it.expenses }), includeZero = true)
            ReportMetric.CASH_FLOW -> drawSeriesBars(points, listOf(ChartSeries(colors.positive) { it.income }, ChartSeries(colors.negative) { it.expenses }))
            ReportMetric.SPENDING -> drawSeriesBars(points, listOf(ChartSeries(colors.negative) { it.expenses }))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeriesLines(
    points: List<SeriesPoint>,
    grid: Color,
    lines: List<ChartSeries>,
    includeZero: Boolean,
) {
    if (points.isEmpty()) return
    val all = lines.flatMap { line -> points.map { line.value(it) } }
    val min = (if (includeZero) minOf(0L, all.min()) else all.min()).toFloat()
    val max = (if (includeZero) maxOf(0L, all.max()) else all.max()).toFloat()
    val span = (max - min).takeIf { it > 0f } ?: 1f
    val step = if (points.size > 1) size.width / (points.size - 1) else 0f
    for (i in 0..2) {
        val y = size.height * i / 2f
        drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }
    for (line in lines) {
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = index * step
            val y = size.height - (line.value(point).toFloat() - min) / span * size.height * 0.9f - size.height * 0.05f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            if (points.size <= 40) drawCircle(line.color, radius = 3.dp.toPx(), center = Offset(x, y))
        }
        drawPath(path, line.color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeriesBars(points: List<SeriesPoint>, bars: List<ChartSeries>) {
    if (points.isEmpty()) return
    val maxValue = points.maxOf { point -> bars.maxOf { bar -> kotlin.math.abs(bar.value(point)) } }.takeIf { it > 0 } ?: 1L
    val slot = size.width / points.size
    val barWidth = (slot * 0.7f / bars.size).coerceAtLeast(1f)
    points.forEachIndexed { index, point ->
        bars.forEachIndexed { barIndex, bar ->
            val height = size.height * 0.95f * kotlin.math.abs(bar.value(point)) / maxValue
            val x = index * slot + slot * 0.15f + barIndex * barWidth
            drawRoundRect(bar.color, Offset(x, size.height - height), Size(barWidth, height), CornerRadius(3f, 3f))
        }
    }
}

/** Actualist's filter sheet: off-budget, hidden and uncategorized switches, account and category lists. */
@Composable
private fun FilterSheet(
    metric: ReportMetric,
    catalog: SeriesCatalog,
    initial: SeriesFilters,
    onApply: (SeriesFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    val accountIds = catalog.accounts.map { it.id }.toSet()
    val categories = catalog.categories(metric)
    val categoryIds = categories.map { it.id }.toSet()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().testTag("explorer-filter-sheet"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Filters", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { draft = SeriesFilters() }, modifier = Modifier.testTag("filter-reset")) { Text("Reset Filters") }
                    Button(onClick = { onApply(draft) }, modifier = Modifier.testTag("filter-apply")) { Text("Apply") }
                }
            }
            item { SwitchRow("Include off-budget accounts", draft.includeOffBudget, "filter-off-budget") { draft = draft.copy(includeOffBudget = it) } }
            if (metric.supportsCategoryFilters) {
                item { SwitchRow("Include hidden categories", draft.includeHiddenCategories, "filter-hidden") { draft = draft.copy(includeHiddenCategories = it) } }
                item { SwitchRow("Include uncategorized", draft.includeUncategorized, "filter-uncategorized") { draft = draft.copy(includeUncategorized = it) } }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Accounts", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { draft = draft.copy(accounts = null) }, modifier = Modifier.testTag("filter-accounts-all")) { Text("All") }
                    TextButton(onClick = { draft = draft.clearAccounts() }, modifier = Modifier.testTag("filter-accounts-none")) { Text("None") }
                }
            }
            items(catalog.accounts, key = { "account-${it.id}" }) { account ->
                val subtitle = listOfNotNull(if (account.isOffBudget) "Off budget" else null, if (account.isClosed) "Closed" else null).joinToString(" · ")
                CheckRow(account.name, subtitle, draft.isAccountSelected(account.id), "filter-account-${account.id}") {
                    draft = draft.withAccount(account.id, it, accountIds)
                }
            }
            if (metric.supportsCategoryFilters) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Categories", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { draft = draft.copy(categories = null) }, modifier = Modifier.testTag("filter-categories-all")) { Text("All") }
                        TextButton(onClick = { draft = draft.clearCategories() }, modifier = Modifier.testTag("filter-categories-none")) { Text("None") }
                    }
                }
                items(categories, key = { "category-${it.id}" }) { category ->
                    val subtitle = listOfNotNull(category.groupName, if (category.isHidden) "Hidden" else null).joinToString(" · ")
                    CheckRow(category.name, subtitle, draft.isCategorySelected(category.id), "filter-category-${category.id}") {
                        draft = draft.withCategory(category.id, it, categoryIds)
                    }
                }
            }
            item { Text("", Modifier.padding(24.dp)) }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun CheckRow(title: String, subtitle: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
