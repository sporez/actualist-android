package com.sporez.actualist.android.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.sporez.actualist.android.ui.Loadable
import com.sporez.actualist.android.ui.components.ErrorBanner
import com.sporez.actualist.android.ui.components.LoadingBox
import com.sporez.actualist.android.ui.components.MessageBox
import com.sporez.actualist.android.ui.components.MoneyText
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.android.ui.reloading
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.transactions.Account
import com.sporez.actualist.core.transactions.TransactionQuery
import com.sporez.actualist.core.transactions.TransactionRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TransactionListState(val rows: List<TransactionRow>, val account: Account?, val uncategorizedCount: Int)

/** A transaction list, optionally scoped to one account or category. */
class TransactionsViewModel(session: BudgetSession, accountId: String?, categoryId: String?) : ViewModel() {
    private val _query = MutableStateFlow(TransactionQuery(accountId = accountId, categoryId = categoryId))
    val query: StateFlow<TransactionQuery> = _query.asStateFlow()

    val state = reloading(session, _query) { q ->
        TransactionListState(
            rows = ledger.transactions(q),
            account = q.accountId?.let { id -> ledger.accounts().firstOrNull { it.id == id } },
            uncategorizedCount = if (q.accountId == null && q.categoryId == null) ledger.uncategorizedCount() else 0,
        )
    }

    fun search(text: String) {
        _query.value = _query.value.copy(search = text)
    }

    fun toggleUncategorized() {
        _query.value = _query.value.copy(uncategorizedOnly = !_query.value.uncategorizedOnly)
    }
}

private val dayFormat = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)

@Composable
fun TransactionsScreen(viewModel: TransactionsViewModel, onOpen: (TransactionRow) -> Unit, onAdd: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val query by viewModel.query.collectAsState()
    Box(Modifier.fillMaxSize().testTag("transactions-screen")) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query.search.orEmpty(),
                onValueChange = viewModel::search,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search payee, category, notes, amount") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).testTag("transaction-search"),
            )
            when (val current = state) {
                Loadable.Loading -> LoadingBox()
                is Loadable.Failed -> ErrorBanner(current.message, Modifier.padding(16.dp))
                is Loadable.Ready -> {
                    val list = current.value
                    if (query.accountId == null && query.categoryId == null && (list.uncategorizedCount > 0 || query.uncategorizedOnly)) {
                        FilterChip(
                            selected = query.uncategorizedOnly,
                            onClick = viewModel::toggleUncategorized,
                            label = { Text("Uncategorized (${list.uncategorizedCount})") },
                            modifier = Modifier.padding(horizontal = 14.dp).testTag("uncategorized-filter"),
                        )
                    }
                    list.account?.let { account ->
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Balance", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                MoneyText(account.balance, colored = true, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("account-balance"))
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Cleared", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                MoneyText(account.clearedBalance)
                            }
                        }
                    }
                    if (list.rows.isEmpty()) {
                        MessageBox("No transactions", if (query.search.isNullOrBlank()) "Add one with the + button." else "Nothing matches that search.")
                    } else {
                        TransactionList(list.rows, showAccount = query.accountId == null, onOpen = onOpen)
                    }
                }
            }
        }
        FloatingActionButton(onClick = onAdd, modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp).testTag("add-transaction")) {
            Icon(Icons.Filled.Add, contentDescription = "Add transaction")
        }
    }
}

@Composable
private fun TransactionList(rows: List<TransactionRow>, showAccount: Boolean, onOpen: (TransactionRow) -> Unit) {
    val byDay = rows.groupBy { it.date }
    LazyColumn(Modifier.testTag("transaction-list"), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((day, dayRows) in byDay) {
            item(key = "day-$day") {
                Text(day?.format(dayFormat) ?: "No date", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
            }
            item(key = "rows-$day") {
                SectionCard {
                    dayRows.forEachIndexed { index, row ->
                        TransactionItem(row, showAccount, onOpen)
                        if (index < dayRows.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionItem(row: TransactionRow, showAccount: Boolean, onOpen: (TransactionRow) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onOpen(row) }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("transaction-${row.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!row.cleared) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.outline, modifier = Modifier.size(7.dp)) {}
                Spacer(Modifier.width(6.dp))
            }
            Text(row.displayPayee.ifBlank { "No payee" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            MoneyText(row.amount, colored = true)
        }
        val detail = buildList {
            when {
                row.isParent -> add("Split · ${row.children.size} categories")
                row.isTransfer && row.categoryName == null -> add("Transfer")
                row.categoryName != null -> add(row.categoryName!!)
                row.startingBalance -> add("Starting balance")
                else -> add("Uncategorized")
            }
            if (showAccount) add(row.accountName)
            row.notes?.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(" · ")
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (row.isParent) {
            for (child in row.children) {
                Row(Modifier.padding(start = 12.dp, top = 2.dp)) {
                    Text("↳ ${child.categoryName ?: "Uncategorized"}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyText(child.amount, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
