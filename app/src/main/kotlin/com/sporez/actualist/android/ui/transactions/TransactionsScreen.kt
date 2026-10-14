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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
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

data class TransactionListState(
    val rows: List<TransactionRow>,
    val account: Account?,
    val uncategorizedCount: Int,
    val savedFilters: List<com.sporez.actualist.core.query.SavedFilter> = emptyList(),
    /** Set when the active saved filter cannot run here (an operator this port does not support). */
    val filterProblem: String? = null,
)

/** A transaction list, optionally scoped to one account or category. */
class TransactionsViewModel(
    private val session: BudgetSession,
    accountId: String?,
    categoryId: String?,
    private val server: com.sporez.actualist.android.sync.ServerConnection? = null,
) : ViewModel() {
    val messages = com.sporez.actualist.android.ui.MessageState()
    private val _query = MutableStateFlow(TransactionQuery(accountId = accountId, categoryId = categoryId))
    val query: StateFlow<TransactionQuery> = _query.asStateFlow()

    private val _filterId = MutableStateFlow<String?>(null)
    val filterId: StateFlow<String?> = _filterId.asStateFlow()

    val state = reloading(session, kotlinx.coroutines.flow.combine(_query, _filterId) { q, f -> q to f }) { (q, filterId) ->
        val queries = com.sporez.actualist.core.query.TransactionQueries(database)
        val filters = runCatching { queries.savedFilters() }.getOrDefault(emptyList())
        val filter = filterId?.let { id -> filters.firstOrNull { it.id == id } }
        var problem: String? = null
        val rows = if (filter == null) {
            ledger.transactions(q)
        } else {
            // The ported query engine decides membership (split families group to their parent);
            // rows render through the same ledger read as the unfiltered list.
            val page = queries.query(filter, accountId = q.accountId, text = q.search?.takeIf { it.isNotBlank() })
            if (page == null) {
                problem = "\"${filter.name}\" uses a condition this app cannot run yet."
                emptyList()
            } else {
                val ids = page.transactions.mapNotNull { it.id }.toSet()
                ledger.transactions(q.copy(search = null, limit = 100_000)).filter { it.id in ids }
            }
        }
        TransactionListState(
            rows = rows,
            account = q.accountId?.let { id -> ledger.accounts().firstOrNull { it.id == id } },
            uncategorizedCount = if (q.accountId == null && q.categoryId == null) ledger.uncategorizedCount() else 0,
            savedFilters = filters,
            filterProblem = problem,
        )
    }

    fun selectFilter(id: String?) {
        _filterId.value = if (_filterId.value == id) null else id
    }

    suspend fun filterChoices(field: String): List<com.sporez.actualist.android.ui.components.PickerOption> = session.read {
        when (field) {
            "payee" -> ledger.payees().filter { !it.isTransfer }.map { com.sporez.actualist.android.ui.components.PickerOption(it.id, it.name) }
            "category" -> ledger.categories(includeHidden = true).map { com.sporez.actualist.android.ui.components.PickerOption(it.id, it.name, it.groupName) }
            else -> ledger.accounts().map { com.sporez.actualist.android.ui.components.PickerOption(it.id, it.name) }
        }
    }

    /** Saves "<field> is <value>" as an Actual saved filter (duplicate names and conditions are refused). */
    fun createFilter(name: String, field: String, valueId: String) {
        viewModelScope.launch {
            try {
                val id = session.write {
                    com.sporez.actualist.core.query.TransactionQueries(database).createFilter(
                        name,
                        listOf(com.sporez.actualist.core.rules.RuleCondition(field, "is", com.sporez.actualist.core.rules.RuleValue.Text(valueId), "id")),
                        com.sporez.actualist.core.rules.RuleConditionJoin.AND,
                    )
                }
                _filterId.value = id
                messages.show("Saved filter $name")
            } catch (error: Exception) {
                messages.show(error.message ?: "The filter could not be saved.")
            }
        }
    }

    fun deleteFilter(id: String) {
        viewModelScope.launch {
            try {
                session.write { com.sporez.actualist.core.query.TransactionQueries(database).deleteFilter(id) }
                if (_filterId.value == id) _filterId.value = null
                messages.show("Filter deleted")
            } catch (error: Exception) {
                messages.show(error.message ?: "The filter could not be deleted.")
            }
        }
    }

    fun search(text: String) {
        _query.value = _query.value.copy(search = text)
    }

    fun toggleUncategorized() {
        _query.value = _query.value.copy(uncategorizedOnly = !_query.value.uncategorizedOnly)
    }

    /** Cleared balance for the reconcile dialog, read fresh each time it opens. */
    suspend fun clearedBalance(): Long? = _query.value.accountId?.let { id -> session.read { com.sporez.actualist.core.transactions.Reconciliation(database, clock).snapshot(id).clearedBalance } }

    fun reconcile(bankBalance: Long, finish: Boolean) {
        val accountId = _query.value.accountId ?: return
        viewModelScope.launch {
            try {
                session.write {
                    val reconciliation = com.sporez.actualist.core.transactions.Reconciliation(database, clock)
                    reconciliation.createAdjustment(accountId, bankBalance, java.time.LocalDate.now())
                    if (finish) reconciliation.finish(accountId, bankBalance)
                }
                messages.show(if (finish) "Reconciled and locked" else "Adjustment added")
            } catch (error: Exception) {
                messages.show(error.message ?: "Reconciliation failed.")
            }
        }
    }

    /** The close review for this account (what closing would do, or why it cannot yet). */
    suspend fun closeReview(destinationId: String?, categoryId: String?): com.sporez.actualist.core.transactions.AccountCloseReview? =
        _query.value.accountId?.let { id -> session.read { com.sporez.actualist.core.transactions.AccountLifecycle(database, clock).review(id, destinationId, categoryId) } }

    suspend fun closeChoices(): Pair<List<com.sporez.actualist.core.transactions.Account>, List<com.sporez.actualist.core.transactions.CategoryOption>> =
        session.read { ledger.accounts(includeClosed = false) to ledger.categories(includeHidden = true).filter { !it.isIncome } }

    fun closeAccount(destinationId: String?, categoryId: String?) = accountWrite("Account closed") {
        com.sporez.actualist.core.transactions.AccountLifecycle(database, clock).close(it, destinationId, categoryId, java.time.LocalDate.now())
    }

    fun reopenAccount() = accountWrite("Account reopened") { com.sporez.actualist.core.transactions.AccountLifecycle(database, clock).reopen(it) }

    fun renameAccount(name: String) = accountWrite("Renamed") { com.sporez.actualist.core.transactions.AccountLifecycle(database, clock).rename(it, name) }

    private fun accountWrite(done: String, block: BudgetSession.(String) -> Any?) {
        val accountId = _query.value.accountId ?: return
        viewModelScope.launch {
            try {
                session.write { block(accountId) }
                messages.show(done)
            } catch (error: Exception) {
                messages.show(error.message ?: "The account could not be changed.")
            }
        }
    }

    /** SimpleFIN bank sync is offered for server budgets; the server holds the SimpleFIN token. */
    val bankSyncAvailable: Boolean get() = server != null && !session.isDemo && _query.value.accountId != null

    suspend fun bankLink(): com.sporez.actualist.core.banksync.SimpleFinLinkedAccount? {
        val id = _query.value.accountId ?: return null
        val connection = server ?: return null
        return session.read { connection.simpleFin(database, clock).linkedAccounts().firstOrNull { it.id == id && it.isSimpleFin } }
    }

    /** The server's SimpleFIN accounts; throws with a readable message when the server cannot sync. */
    suspend fun simpleFinAccounts(): List<com.sporez.actualist.core.banksync.SimpleFinRemoteAccount> {
        val connection = server ?: return emptyList()
        return session.read { connection.simpleFin(database, clock).remoteAccounts() }
    }

    fun linkBank(remote: com.sporez.actualist.core.banksync.SimpleFinRemoteAccount) = accountWrite("Linked to ${remote.name}") {
        server!!.simpleFin(database, clock).link(it, remote)
    }

    fun unlinkBank() = accountWrite("Unlinked from the bank") { server!!.simpleFin(database, clock).unlink(it) }

    /** Downloads and imports this account's bank transactions (not an undoable edit, like a server sync). */
    fun syncBank() {
        val accountId = _query.value.accountId ?: return
        val connection = server ?: return
        viewModelScope.launch {
            try {
                val summary = session.syncing { connection.simpleFin(database, clock).sync(accountId) }
                messages.show(summary.text())
            } catch (error: Exception) {
                messages.show(error.message ?: "Bank sync failed.")
            }
        }
    }

    fun exportFilename(): String = com.sporez.actualist.core.csv.CsvExport.filename(System.currentTimeMillis())

    /** Writes Actual's CSV export format for this account (or every account) to [uri]. */
    fun exportCsv(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val count = session.read {
                    val rows = com.sporez.actualist.core.csv.CsvExport.rows(database, _query.value.accountId)
                    val text = com.sporez.actualist.core.csv.CsvExport.encode(rows)
                    resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("The file could not be opened.")
                    rows.size
                }
                messages.show("Exported $count rows")
            } catch (error: Exception) {
                messages.show(error.message ?: "Export failed.")
            }
        }
    }

    /** Imports a CSV into this account in one transaction; nothing is written on any error. */
    fun importCsv(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        val accountId = _query.value.accountId ?: return
        viewModelScope.launch {
            try {
                val plan = session.write {
                    val bytes = resolver.openInputStream(uri)?.use { it.readNBytesCompat(com.sporez.actualist.core.csv.CsvImportMapper.MAXIMUM_BYTES + 1) }
                        ?: error("The file could not be opened.")
                    if (bytes.size > com.sporez.actualist.core.csv.CsvImportMapper.MAXIMUM_BYTES) error("The file is larger than 10 MB.")
                    com.sporez.actualist.core.csv.CsvImporter(database, clock).import(accountId, bytes.toString(Charsets.UTF_8))
                }
                messages.show(
                    listOfNotNull(
                        "Imported ${plan.imported} rows",
                        plan.updated.takeIf { it > 0 }?.let { "updated $it" },
                        plan.duplicates.takeIf { it > 0 }?.let { "$it already present" },
                        plan.skipped.takeIf { it > 0 }?.let { "skipped $it" },
                    ).joinToString(", "),
                )
            } catch (error: Exception) {
                messages.show(error.message ?: "Import failed.")
            }
        }
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private val dayFormat = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)

@Composable
fun TransactionsScreen(viewModel: TransactionsViewModel, onOpen: (TransactionRow) -> Unit, onAdd: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val query by viewModel.query.collectAsState()
    val message by viewModel.messages.message.collectAsState()
    val resolver = LocalContext.current.contentResolver
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { viewModel.exportCsv(resolver, it) } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importCsv(resolver, it) } }
    var reconciling by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (reconciling) ReconcileDialog(viewModel) { reconciling = false }
    var managing by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (managing) {
        val current = (state as? Loadable.Ready)?.value?.account
        if (current != null) AccountDialog(current, viewModel) { managing = false }
    }
    Box(Modifier.fillMaxSize().testTag("transactions-screen")) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { exportLauncher.launch(viewModel.exportFilename()) }, modifier = Modifier.testTag("export-csv")) { Text("Export CSV") }
                if (query.accountId != null) {
                    TextButton(onClick = { reconciling = true }, modifier = Modifier.testTag("reconcile")) { Text("Reconcile") }
                    TextButton(onClick = { managing = true }, modifier = Modifier.testTag("manage-account")) { Text("Account") }
                    TextButton(onClick = { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream")) }, modifier = Modifier.testTag("import-csv")) { Text("Import CSV") }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).testTag("transactions-message")) }
            }
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
                    FilterChipsRow(list, viewModel)
                    list.filterProblem?.let { ErrorBanner(it, Modifier.padding(horizontal = 14.dp)) }
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
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // LazyColumn keeps its position on the first visible key, so a new newest row (a new day
    // group) would be inserted above the viewport. Follow it when the list was at the top.
    androidx.compose.runtime.LaunchedEffect(rows.firstOrNull()?.id) {
        if (listState.firstVisibleItemIndex <= 3) listState.scrollToItem(0)
    }
    LazyColumn(Modifier.testTag("transaction-list"), state = listState, contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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


@Composable
private fun ReconcileDialog(viewModel: TransactionsViewModel, onDismiss: () -> Unit) {
    var cleared by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }
    var balanceText by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(Unit) { cleared = viewModel.clearedBalance() }
    val bank = com.sporez.actualist.core.money.Money.parse(balanceText)
    val difference = if (bank != null && cleared != null) bank - cleared!! else null
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reconcile account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cleared balance: " + (cleared?.let { com.sporez.actualist.core.money.Money.format(it) } ?: "…"), modifier = Modifier.testTag("reconcile-cleared"))
                com.sporez.actualist.android.ui.components.AmountField(balanceText, { balanceText = it }, label = "Bank statement balance", tag = "reconcile-balance")
                difference?.let {
                    Text(
                        if (it == 0L) "Balanced. Finishing locks every cleared transaction." else "Difference ${com.sporez.actualist.core.money.Money.format(it)} will be added as a cleared adjustment.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("reconcile-difference"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = bank != null && cleared != null, onClick = { viewModel.reconcile(bank!!, finish = true); onDismiss() }, modifier = Modifier.testTag("reconcile-finish")) {
                Text(if (difference == 0L) "Finish" else "Adjust and finish")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


/** Rename, close (moving any balance to another account, as Actual does) or reopen. */
@Composable
private fun AccountDialog(account: Account, viewModel: TransactionsViewModel, onDismiss: () -> Unit) {
    var name by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(account.name) }
    var destinationId by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var categoryId by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var picking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var review by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.sporez.actualist.core.transactions.AccountCloseReview?>(null) }
    var choices by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Pair<List<Account>, List<com.sporez.actualist.core.transactions.CategoryOption>>?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(destinationId, categoryId) {
        review = viewModel.closeReview(destinationId, categoryId)
        if (choices == null) choices = viewModel.closeChoices()
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(account.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("account-rename"))
                TextButton(onClick = { viewModel.renameAccount(name); onDismiss() }, enabled = name.isNotBlank() && name.trim() != account.name) { Text("Rename") }
                if (viewModel.bankSyncAvailable && !account.closed) {
                    androidx.compose.material3.HorizontalDivider()
                    BankSyncSection(viewModel, onDismiss)
                }
                androidx.compose.material3.HorizontalDivider()
                if (account.closed) {
                    TextButton(onClick = { viewModel.reopenAccount(); onDismiss() }, modifier = Modifier.testTag("reopen-account")) { Text("Reopen account") }
                } else {
                    val r = review
                    when (r?.action) {
                        com.sporez.actualist.core.transactions.AccountCloseAction.DeleteEmpty -> Text("No transactions: closing deletes this account.", style = MaterialTheme.typography.bodySmall)
                        com.sporez.actualist.core.transactions.AccountCloseAction.CloseAtZero -> Text("The balance is zero.", style = MaterialTheme.typography.bodySmall)
                        else -> if (r != null && r.balance != 0L) {
                            Text("Move the ${com.sporez.actualist.core.money.Money.format(r.balance)} balance to:", style = MaterialTheme.typography.bodySmall)
                            val accounts = choices?.first.orEmpty().filter { it.id != account.id }
                            TextButton(onClick = { picking = "account" }, modifier = Modifier.testTag("close-destination")) {
                                Text(accounts.firstOrNull { it.id == destinationId }?.name ?: "Choose an account")
                            }
                            if (r.blockers.contains(com.sporez.actualist.core.transactions.AccountCloseBlocker.CATEGORY_REQUIRED) || categoryId != null) {
                                TextButton(onClick = { picking = "category" }, modifier = Modifier.testTag("close-category")) {
                                    Text(choices?.second.orEmpty().firstOrNull { it.id == categoryId }?.name ?: "Choose a category")
                                }
                            }
                        }
                    }
                    r?.blockers?.filter { it != com.sporez.actualist.core.transactions.AccountCloseBlocker.DESTINATION_REQUIRED }?.firstOrNull()?.let {
                        Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(
                        onClick = { viewModel.closeAccount(destinationId, categoryId); onDismiss() },
                        enabled = r?.action != null,
                        modifier = Modifier.testTag("close-account"),
                    ) { Text(if (r?.action == com.sporez.actualist.core.transactions.AccountCloseAction.DeleteEmpty) "Delete account" else "Close account", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    when (picking) {
        "account" -> com.sporez.actualist.android.ui.components.PickerDialog(
            title = "Move balance to",
            options = choices?.first.orEmpty().filter { it.id != account.id }.map {
                com.sporez.actualist.android.ui.components.PickerOption(it.id, it.name, if (it.offBudget) "Off budget" else "On budget")
            },
            onPick = { destinationId = it.id; picking = null },
            onDismiss = { picking = null },
        )
        "category" -> com.sporez.actualist.android.ui.components.PickerDialog(
            title = "Category",
            options = choices?.second.orEmpty().map { com.sporez.actualist.android.ui.components.PickerOption(it.id, it.name, it.groupName) },
            searchable = true,
            onPick = { categoryId = it.id; picking = null },
            onDismiss = { picking = null },
        )
    }
}


@Composable
private fun FilterChipsRow(list: TransactionListState, viewModel: TransactionsViewModel) {
    val active by viewModel.filterId.collectAsState()
    var creating by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth().testTag("saved-filters"),
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(list.savedFilters, key = { it.id }) { filter ->
            FilterChip(
                selected = active == filter.id,
                onClick = { viewModel.selectFilter(filter.id) },
                label = { Text(filter.name) },
                modifier = Modifier.testTag("filter-${filter.id}"),
            )
        }
        item {
            FilterChip(selected = false, onClick = { creating = true }, label = { Text("+ Filter") }, modifier = Modifier.testTag("filter-new"))
        }
        active?.let { id ->
            item { TextButton(onClick = { viewModel.deleteFilter(id) }, modifier = Modifier.testTag("filter-delete")) { Text("Delete filter") } }
        }
    }
    if (creating) NewFilterDialog(viewModel) { creating = false }
}

@Composable
private fun NewFilterDialog(viewModel: TransactionsViewModel, onDismiss: () -> Unit) {
    var name by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var field by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("payee") }
    var value by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.sporez.actualist.android.ui.components.PickerOption?>(null) }
    var choices by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<com.sporez.actualist.android.ui.components.PickerOption>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New filter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("filter-name"))
                Row {
                    for (option in listOf("payee", "category", "account")) {
                        FilterChip(
                            selected = field == option,
                            onClick = { field = option; value = null },
                            label = { Text(option.replaceFirstChar { it.uppercase() }) },
                            modifier = Modifier.padding(end = 6.dp).testTag("filter-field-$option"),
                        )
                    }
                }
                TextButton(onClick = { scope.launch { choices = viewModel.filterChoices(field) } }, modifier = Modifier.testTag("filter-value")) {
                    Text(value?.let { "is ${it.title}" } ?: "Choose a ${field}")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && value != null,
                onClick = { value?.id?.let { viewModel.createFilter(name.trim(), field, it) }; onDismiss() },
                modifier = Modifier.testTag("filter-save"),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    choices?.let { options ->
        com.sporez.actualist.android.ui.components.PickerDialog(
            title = field.replaceFirstChar { it.uppercase() },
            options = options,
            searchable = true,
            onPick = { value = it; choices = null },
            onDismiss = { choices = null },
        )
    }
}


/** Link this account to a SimpleFIN account on the server, sync it, or unlink it. */
@Composable
private fun BankSyncSection(viewModel: TransactionsViewModel, onDismiss: () -> Unit) {
    var link by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.sporez.actualist.core.banksync.SimpleFinLinkedAccount?>(null) }
    var loaded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var remotes by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<com.sporez.actualist.core.banksync.SimpleFinRemoteAccount>?>(null) }
    var problem by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        link = runCatching { viewModel.bankLink() }.getOrNull()
        loaded = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("bank-sync-section")) {
        Text("Bank sync (SimpleFIN)", style = MaterialTheme.typography.labelLarge)
        val linked = link
        when {
            !loaded -> Text("Checking…", style = MaterialTheme.typography.bodySmall)
            linked != null -> Row {
                TextButton(onClick = { viewModel.syncBank(); onDismiss() }, modifier = Modifier.testTag("bank-sync")) { Text("Sync bank") }
                TextButton(onClick = { viewModel.unlinkBank(); onDismiss() }, modifier = Modifier.testTag("bank-unlink")) { Text("Unlink") }
            }
            else -> TextButton(
                onClick = {
                    scope.launch {
                        problem = null
                        try {
                            remotes = viewModel.simpleFinAccounts()
                        } catch (error: Exception) {
                            problem = error.message ?: "The server cannot reach SimpleFIN."
                        }
                    }
                },
                modifier = Modifier.testTag("bank-link"),
            ) { Text("Link to a SimpleFIN account…") }
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    remotes?.let { list ->
        com.sporez.actualist.android.ui.components.PickerDialog(
            title = if (list.isEmpty()) "No SimpleFIN accounts on this server" else "SimpleFIN account",
            options = list.map { com.sporez.actualist.android.ui.components.PickerOption(it.accountId, it.name, listOfNotNull(it.orgName ?: it.institution, it.balance).joinToString(" · ")) },
            onPick = { option ->
                list.firstOrNull { it.accountId == option.id }?.let { viewModel.linkBank(it) }
                remotes = null
                onDismiss()
            },
            onDismiss = { remotes = null },
        )
    }
}
