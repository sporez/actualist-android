package com.sporez.actualist.android.ui.reports

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.Loadable
import com.sporez.actualist.android.ui.components.LoadingBox
import com.sporez.actualist.android.ui.components.MoneyText
import com.sporez.actualist.core.reports.DrilldownGroup
import com.sporez.actualist.core.reports.DrilldownKind
import com.sporez.actualist.core.reports.DrilldownResult
import com.sporez.actualist.core.reports.DrilldownRole
import com.sporez.actualist.core.reports.DrilldownRow
import com.sporez.actualist.core.reports.ExplorerFilter
import com.sporez.actualist.core.reports.ReportDrilldown
import com.sporez.actualist.core.reports.ReportSeries
import com.sporez.actualist.core.store.BudgetSession
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The route pattern for [DrilldownScreen]; register it with these four string arguments (all default to empty). */
const val DRILLDOWN_ROUTE_PATTERN = "drilldown?start={start}&end={end}&accounts={accounts}&categories={categories}&title={title}"

/** The navigation route that opens [DrilldownScreen] for a report range and filters. */
fun drilldownRoute(start: LocalDate, end: LocalDate, accountIds: Set<String>?, categoryIds: Set<String>?, title: String = "Transactions"): String =
    "drilldown?start=$start&end=$end" +
        "&accounts=${Uri.encode(accountIds.orEmpty().joinToString(","))}" +
        "&categories=${Uri.encode(categoryIds.orEmpty().joinToString(","))}" +
        "&title=${Uri.encode(title)}"

private class DrilldownData(val result: DrilldownResult, val names: Map<String, String>)

/** "View Contributing Transactions", as Actualist's Report Explorer offers under the totals. */
@Composable
fun ViewContributingTransactionsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.fillMaxWidth().testTag("report-drilldown-button")) {
        Text("View Contributing Transactions")
    }
}

/**
 * Actualist's `ReportTransactionDrilldownView`: every transaction behind a report range, grouped by day, each
 * labelled "Counted" or "Context only" (a split family's other lines), with split lines and parents marked.
 * [accounts] and [categories] are comma-separated ids (empty means all). A tap opens the transaction.
 */
@Composable
fun DrilldownScreen(
    session: BudgetSession,
    start: String,
    end: String,
    accounts: String,
    categories: String,
    onOpenTransaction: (String) -> Unit,
) {
    val revision by session.revision.collectAsState()
    var attempt by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<Loadable<DrilldownData>>(Loadable.Loading) }
    LaunchedEffect(start, end, accounts, categories, revision, attempt) {
        try {
            val filter = ExplorerFilter(
                start = LocalDate.parse(start),
                end = LocalDate.parse(end),
                accountIds = accounts.split(',').filter { it.isNotBlank() }.toSet().takeIf { it.isNotEmpty() },
                categoryIds = categories.split(',').filter { it.isNotBlank() }.toSet().takeIf { it.isNotEmpty() },
            )
            state = session.read {
                val result = ReportDrilldown(database).load(filter)
                val catalog = ReportSeries(database).catalog()
                DrilldownData(
                    result,
                    (catalog.accounts.map { it.id to it.name } + catalog.categories.map { it.id to it.name }).toMap(),
                ).let { Loadable.Ready(it) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state = Loadable.Failed(error.message ?: "These transactions could not be loaded.")
        }
    }
    when (val current = state) {
        Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> Column(Modifier.fillMaxSize().padding(16.dp).testTag("drilldown-error"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Transactions Unavailable", style = MaterialTheme.typography.titleMedium)
            Text(current.message)
            TextButton(onClick = { state = Loadable.Loading; attempt += 1 }, modifier = Modifier.testTag("drilldown-retry")) { Text("Retry") }
        }
        is Loadable.Ready -> DrilldownList(current.value, onOpenTransaction)
    }
}

private fun dayTitle(date: String): String =
    runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }.getOrDefault(date)

@Composable
private fun DrilldownList(data: DrilldownData, onOpenTransaction: (String) -> Unit) {
    val count = data.result.countedCount
    LazyColumn(Modifier.fillMaxSize().testTag("drilldown-screen")) {
        item {
            Text(
                "$count contributing transaction${if (count == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(14.dp).testTag("drilldown-count"),
            )
        }
        if (data.result.groups.isEmpty()) {
            item { Text("Nothing matches these filters.", modifier = Modifier.padding(14.dp).testTag("drilldown-empty")) }
        }
        for (group in data.result.groups) {
            item(key = "day-${group.date}") { DayHeader(group) }
            items(group.rows, key = { "row-${it.id}" }) { row -> DrilldownRowItem(row, data.names, onOpenTransaction) }
        }
        item { Row(Modifier.padding(bottom = 28.dp)) {} }
    }
}

@Composable
private fun DayHeader(group: DrilldownGroup) {
    Text(
        dayTitle(group.date),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).testTag("drilldown-day-${group.date}"),
    )
}

@Composable
private fun DrilldownRowItem(row: DrilldownRow, names: Map<String, String>, onOpenTransaction: (String) -> Unit) {
    val transaction = row.transaction
    val target = transaction.parentId ?: transaction.id
    val counted = row.role == DrilldownRole.COUNTED
    Column(
        Modifier.fillMaxWidth()
            .padding(start = if (row.kind == DrilldownKind.SPLIT_CHILD) 22.dp else 0.dp)
            .clickable(enabled = target != null) { target?.let(onOpenTransaction) }
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag("drilldown-row-${row.id}"),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                when (row.kind) {
                    DrilldownKind.SPLIT_CHILD -> "Split line"
                    DrilldownKind.SPLIT_PARENT -> "Split transaction"
                    DrilldownKind.ROOT -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (counted) "Counted" else "Context only",
                style = MaterialTheme.typography.labelSmall,
                color = if (counted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag((if (counted) "drilldown-counted-" else "drilldown-context-") + row.id),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(transaction.payeeName ?: transaction.importedPayee ?: "No payee", maxLines = 1)
                val category = transaction.categoryId?.let { names[it] } ?: if (row.kind == DrilldownKind.SPLIT_PARENT) "Split" else "Uncategorized"
                Text(
                    "${names[transaction.accountId] ?: "Unknown Account"} · $category",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MoneyText(transaction.amount ?: 0L, colored = true)
        }
    }
    HorizontalDivider()
}
