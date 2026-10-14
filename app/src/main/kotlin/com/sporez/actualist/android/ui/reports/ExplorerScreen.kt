package com.sporez.actualist.android.ui.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.ViewModel
import com.sporez.actualist.android.ui.Loadable
import com.sporez.actualist.android.ui.components.ErrorBanner
import com.sporez.actualist.android.ui.components.LoadingBox
import com.sporez.actualist.android.ui.components.MoneyText
import com.sporez.actualist.android.ui.components.PickerDialog
import com.sporez.actualist.android.ui.components.PickerOption
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.android.ui.reloading
import com.sporez.actualist.core.reports.ExplorerFilter
import com.sporez.actualist.core.reports.ExplorerResult
import com.sporez.actualist.core.reports.ReportExplorer
import com.sporez.actualist.core.store.BudgetSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.YearMonth

data class ExplorerState(
    val result: ExplorerResult,
    val accounts: List<PickerOption>,
    val categories: List<PickerOption>,
)

/** Actualist's Report Explorer: a date range, accounts and categories, with totals and matching transactions. */
class ExplorerViewModel(session: BudgetSession, today: LocalDate = LocalDate.now()) : ViewModel() {
    private val _filter = MutableStateFlow(ExplorerFilter(YearMonth.from(today).atDay(1), today))
    val filter: StateFlow<ExplorerFilter> = _filter

    val state = reloading(session, _filter) { filter ->
        ExplorerState(
            result = ReportExplorer(database).explore(filter),
            accounts = ledger.accounts().map { PickerOption(it.id, it.name) },
            categories = ledger.categories(includeHidden = true).map { PickerOption(it.id, it.name, it.groupName) },
        )
    }

    fun setRange(start: LocalDate, end: LocalDate) {
        _filter.value = _filter.value.copy(start = start, end = end)
    }

    fun toggleAccount(id: String) {
        _filter.value = _filter.value.copy(accountIds = toggle(_filter.value.accountIds, id))
    }

    fun toggleCategory(id: String) {
        _filter.value = _filter.value.copy(categoryIds = toggle(_filter.value.categoryIds, id))
    }

    fun setIncludeTransfers(include: Boolean) {
        _filter.value = _filter.value.copy(includeTransfers = include)
    }

    /** Null (everything) when the last one is removed. */
    private fun toggle(current: Set<String>?, id: String): Set<String>? {
        val next = current.orEmpty().let { if (id in it) it - id else it + id }
        return next.ifEmpty { null }
    }
}

@Composable
fun ExplorerScreen(viewModel: ExplorerViewModel, today: LocalDate = LocalDate.now()) {
    val state by viewModel.state.collectAsState()
    val filter by viewModel.filter.collectAsState()
    var picking by remember { mutableStateOf<String?>(null) }
    var focusCategory by remember { mutableStateOf<String?>(null) }
    val ranges = listOf(
        "This month" to (YearMonth.from(today).atDay(1) to today),
        "Last month" to (YearMonth.from(today).minusMonths(1).let { it.atDay(1) to it.atEndOfMonth() }),
        "Last 3 months" to (YearMonth.from(today).minusMonths(2).atDay(1) to today),
        "This year" to (LocalDate.of(today.year, 1, 1) to today),
        "Last 12 months" to (YearMonth.from(today).minusMonths(11).atDay(1) to today),
    )
    when (val current = state) {
        Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBanner(current.message, Modifier.padding(16.dp))
        is Loadable.Ready -> {
            val explorer = current.value
            val names = (explorer.accounts + explorer.categories).associate { (it.id ?: "") to it.title }
            val rows = explorer.result.rows.filter { focusCategory == null || it.categoryId == focusCategory }
            LazyColumn(Modifier.fillMaxSize().testTag("explorer-screen"), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(ranges, key = { it.first }) { (label, range) ->
                            FilterChip(
                                selected = filter.start == range.first && filter.end == range.second,
                                onClick = { viewModel.setRange(range.first, range.second) },
                                label = { Text(label) },
                                modifier = Modifier.testTag("explorer-range-$label"),
                            )
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { picking = "accounts" }, modifier = Modifier.testTag("explorer-accounts")) {
                            Text(filter.accountIds?.let { "${it.size} account(s)" } ?: "All accounts")
                        }
                        TextButton(onClick = { picking = "categories" }, modifier = Modifier.testTag("explorer-categories")) {
                            Text(filter.categoryIds?.let { "${it.size} category(ies)" } ?: "All categories")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = filter.includeTransfers, onCheckedChange = viewModel::setIncludeTransfers)
                        Text("Include transfers")
                    }
                }
                item {
                    SectionCard(Modifier.testTag("explorer-totals")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${filter.start} – ${filter.end}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TotalRow("Income", explorer.result.income)
                            TotalRow("Expenses", explorer.result.expenses)
                            HorizontalDivider()
                            TotalRow("Net", explorer.result.net)
                        }
                    }
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
                items(rows.take(100), key = { it.id ?: it.hashCode().toString() }) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp).testTag("explorer-row")) {
                        Column(Modifier.weight(1f)) {
                            Text(row.payeeName ?: row.importedPayee ?: "No payee", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${row.date} · ${names[row.accountId] ?: row.accountId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        MoneyText(row.amount ?: 0L, colored = true)
                    }
                }
                if (rows.size > 100) item { Text("Showing the newest 100 of ${rows.size}.", style = MaterialTheme.typography.bodySmall) }
            }
            when (picking) {
                "accounts" -> PickerDialog(
                    title = "Toggle an account",
                    options = explorer.accounts.map { it.copy(subtitle = if (filter.accountIds?.contains(it.id) == true) "Included" else null) },
                    onPick = { option -> option.id?.let(viewModel::toggleAccount); picking = null },
                    onDismiss = { picking = null },
                )
                "categories" -> PickerDialog(
                    title = "Toggle a category",
                    options = explorer.categories.map { it.copy(subtitle = if (filter.categoryIds?.contains(it.id) == true) "Included" else it.subtitle) },
                    searchable = true,
                    onPick = { option -> option.id?.let(viewModel::toggleCategory); picking = null },
                    onDismiss = { picking = null },
                )
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
