package com.sporez.actualist.android.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import com.sporez.actualist.core.transactions.BatchIntent
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
    /** Transactions a sync brought in that have not been looked at yet (local only; Actualist's "new" marker). */
    val pendingNewIds: Set<String> = emptySet(),
)

/** Conditions applied to the feed, saved or not. */
data class AppliedFilter(
    val conditions: List<com.sporez.actualist.core.query.QueryCondition>,
    val join: com.sporez.actualist.core.query.QueryJoin,
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

    /** The conditions currently applied (a saved filter's, or ones applied from the sheet without saving). */
    private val _adhoc = MutableStateFlow<AppliedFilter?>(null)
    val adhoc: StateFlow<AppliedFilter?> = _adhoc.asStateFlow()

    val state = reloading(session, kotlinx.coroutines.flow.combine(_query, _filterId, _adhoc) { q, f, a -> Triple(q, f, a) }) { (q, filterId, applied) ->
        val queries = com.sporez.actualist.core.query.TransactionQueries(database)
        val filters = runCatching { queries.savedFilters() }.getOrDefault(emptyList())
        val filter = filterId?.let { id -> filters.firstOrNull { it.id == id } }
        var problem: String? = null
        val rows = if (applied == null && filterId == null) {
            ledger.transactions(q)
        } else {
            // The ported query engine decides membership (split families group to their parent);
            // rows render through the same ledger read as the unfiltered list.
            val text = q.search?.takeIf { it.isNotBlank() }
            val page = if (applied != null) {
                queries.query(accountId = q.accountId, conditions = applied.conditions, join = applied.join, text = text)
            } else {
                filter?.let { queries.query(it, accountId = q.accountId, text = text) }
            }
            if (page == null) {
                problem = "\"${filter?.name ?: "This filter"}\" uses a condition this app cannot run yet."
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
            pendingNewIds = runCatching { com.sporez.actualist.core.notifications.PendingNewTransactions(database).pendingIds(q.accountId) }.getOrDefault(emptySet()),
        )
    }

    /** Marks the new transactions shown as reviewed, as Actualist does when the feed goes away. */
    fun reviewShown(ids: Set<String>) {
        if (ids.isEmpty()) return
        val accountId = _query.value.accountId
        val database = session.database
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { com.sporez.actualist.core.notifications.PendingNewTransactions(database).review(ids, accountId) }
        }
    }

    /** Picks a saved filter (a second tap clears it); its conditions become the applied ones. */
    fun selectFilter(id: String?) {
        if (id == null || _filterId.value == id) {
            _filterId.value = null
            _adhoc.value = null
            return
        }
        _filterId.value = id
        val filter = state.value.let { (it as? Loadable.Ready)?.value?.savedFilters }?.firstOrNull { it.id == id }
        val conditions = filter?.queryConditions
        val join = filter?.queryJoin
        _adhoc.value = if (conditions != null && join != null) AppliedFilter(conditions, join) else null
    }

    /** Applies conditions from the filter sheet without saving them. */
    fun applyFilter(conditions: List<com.sporez.actualist.core.query.QueryCondition>, join: com.sporez.actualist.core.query.QueryJoin) {
        _filterId.value = null
        _adhoc.value = if (conditions.isEmpty()) null else AppliedFilter(conditions, join)
    }

    fun resetFilter() {
        _filterId.value = null
        _adhoc.value = null
    }

    /** Every account, payee and category the filter sheet can pick, keyed by field. */
    suspend fun filterOptions(): Map<com.sporez.actualist.core.query.TransactionFilterField, List<com.sporez.actualist.android.ui.components.PickerOption>> = session.read {
        mapOf(
            com.sporez.actualist.core.query.TransactionFilterField.ACCOUNT to ledger.accounts().map {
                com.sporez.actualist.android.ui.components.PickerOption(it.id, if (it.closed) "${it.name} (Closed)" else it.name)
            },
            com.sporez.actualist.core.query.TransactionFilterField.PAYEE to ledger.payees().filter { !it.isTransfer }.map {
                com.sporez.actualist.android.ui.components.PickerOption(it.id, it.name)
            },
            com.sporez.actualist.core.query.TransactionFilterField.CATEGORY to ledger.categories(includeHidden = true).map {
                com.sporez.actualist.android.ui.components.PickerOption(it.id, it.name, it.groupName)
            },
        )
    }

    private fun failure(error: Exception, fallback: String): String =
        (error as? com.sporez.actualist.core.LocalFirstException.InvalidLocalWrite)?.reason ?: error.message ?: fallback

    /** Saves conditions as an Actual saved filter and applies it (duplicate names and conditions are refused). */
    fun saveFilter(
        name: String,
        conditions: List<com.sporez.actualist.core.query.QueryCondition>,
        join: com.sporez.actualist.core.query.QueryJoin,
    ) {
        viewModelScope.launch {
            try {
                val id = session.write { com.sporez.actualist.core.query.TransactionQueries(database).createFilter(name, conditions, join) }
                _adhoc.value = AppliedFilter(conditions, join)
                _filterId.value = id
                messages.show("Saved filter ${name.trim()}")
            } catch (error: Exception) {
                messages.show(failure(error, "The filter could not be saved."))
            }
        }
    }

    fun renameFilter(id: String, name: String) {
        viewModelScope.launch {
            try {
                session.write { com.sporez.actualist.core.query.TransactionQueries(database).renameFilter(id, name) }
                messages.show("Renamed to ${name.trim()}")
            } catch (error: Exception) {
                messages.show(failure(error, "The filter could not be renamed."))
            }
        }
    }

    /** Replaces a saved filter's conditions with the ones currently applied. */
    fun updateFilterWithCurrent(id: String) {
        val applied = _adhoc.value ?: return
        viewModelScope.launch {
            try {
                session.write { com.sporez.actualist.core.query.TransactionQueries(database).updateFilter(id, applied.conditions, applied.join) }
                _filterId.value = id
                messages.show("Filter updated")
            } catch (error: Exception) {
                messages.show(failure(error, "The filter could not be updated."))
            }
        }
    }

    fun deleteFilter(id: String) {
        viewModelScope.launch {
            try {
                session.write { com.sporez.actualist.core.query.TransactionQueries(database).deleteFilter(id) }
                if (_filterId.value == id) {
                    _filterId.value = null
                    _adhoc.value = null
                }
                messages.show("Filter deleted")
            } catch (error: Exception) {
                messages.show(failure(error, "The filter could not be deleted."))
            }
        }
    }

    fun search(text: String) {
        _query.value = _query.value.copy(search = text)
    }

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    /** Rows selected for a batch action (long-press starts a selection). */
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    fun toggleSelected(id: String) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    /**
     * Runs a batch on the selection. After the user confirmed the reconciled-rows warning
     * ([unlockReconciled]) the lock is lifted for the change and put back after it, so the
     * locked rows are changed rather than skipped.
     */
    private fun batch(
        unlockReconciled: Boolean,
        describe: (com.sporez.actualist.core.transactions.BatchResult) -> String,
        block: BudgetSession.(Set<String>) -> com.sporez.actualist.core.transactions.BatchResult,
    ) {
        val ids = _selection.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                val result = session.write {
                    val mutations = com.sporez.actualist.core.transactions.ReconciledMutations(database)
                    val locked = if (unlockReconciled) mutations.unlock(ids) else emptyList()
                    try {
                        block(ids)
                    } finally {
                        if (locked.isNotEmpty()) mutations.relock(locked)
                    }
                }
                _selection.value = emptySet()
                messages.show(describe(result) + if (result.skipped > 0) " (${result.skipped} skipped: locked or not applicable)" else "")
            } catch (error: Exception) {
                messages.show(error.message ?: "The selected transactions could not be changed.")
            }
        }
    }

    fun clearSelected(cleared: Boolean, unlockReconciled: Boolean = false) = batch(unlockReconciled, { "${if (cleared) "Cleared" else "Uncleared"} ${it.changed}" }) {
        com.sporez.actualist.core.transactions.TransactionBatch(database, clock).setCleared(it, cleared)
    }

    fun categorizeSelected(categoryId: String, unlockReconciled: Boolean = false) = batch(unlockReconciled, { "Categorized ${it.changed}" }) { ids ->
        val result = com.sporez.actualist.core.transactions.TransactionBatch(database, clock).categorize(ids, categoryId)
        com.sporez.actualist.core.rules.CategoryLearning(database).learn(ids, java.time.LocalDate.now())
        result
    }

    fun deleteSelected(unlockReconciled: Boolean = false) = batch(unlockReconciled, { "Deleted ${it.changed}" }) {
        com.sporez.actualist.core.transactions.TransactionBatch(database, clock).delete(it)
    }

    fun duplicateSelected() = batch(false, { "Duplicated ${it.changed}" }) { ids ->
        val result = com.sporez.actualist.core.commands.TransactionCommands(database, clock).duplicate(ids.toList())
        com.sporez.actualist.core.transactions.BatchResult(result.duplicateIds.size, 0)
    }

    private fun listedRows(): List<TransactionRow> = (state.value as? Loadable.Ready)?.value?.rows.orEmpty()

    /** The before → after review of [intent] on the current selection (nothing is written). */
    fun batchReview(intent: com.sporez.actualist.core.transactions.BatchIntent, categoryName: String? = null): com.sporez.actualist.core.transactions.BatchReview =
        com.sporez.actualist.core.transactions.BatchReviewPlanner.plan(intent, _selection.value, listedRows(), categoryName)

    /** The review of merging the two selected transactions; null unless exactly two are selected. */
    suspend fun mergeReview(): MergeReviewState? {
        val ids = _selection.value.toList()
        if (ids.size != 2) return null
        val byId = listedRows().associateBy { it.id }
        var blocked: String? = null
        var keptId: String? = null
        var droppedId: String? = null
        var reconciledCount = 0
        try {
            val preview = session.read { com.sporez.actualist.core.commands.TransactionCommands(database, clock).previewMerge(ids[0], ids[1]) }
            keptId = preview.keeperId
            droppedId = preview.removedId
            reconciledCount = preview.reconciledIds.size
        } catch (error: Exception) {
            blocked = failure(error, "These transactions cannot be merged.")
        }
        val inputs = ids.mapIndexed { index, id ->
            val row = byId[id]
            MergeInputView(
                id = id,
                label = "Input ${index + 1}",
                title = row?.displayPayee?.ifBlank { "No payee" } ?: "Transaction unavailable",
                context = listOfNotNull(row?.date?.format(dayFormat), row?.accountName, row?.notes?.takeIf { it.isNotBlank() }).joinToString(" · "),
                amount = row?.amount ?: 0,
                outcome = when (id) {
                    keptId -> "Kept"
                    droppedId -> "Dropped"
                    else -> null
                },
                detail = when {
                    row == null -> "This transaction is unavailable."
                    id == keptId -> "This transaction is kept."
                    id == droppedId -> "This transaction will be removed."
                    else -> "This transaction will not be changed."
                },
            )
        }
        return MergeReviewState(
            inputs = inputs,
            keptLabel = inputs.firstOrNull { it.id == keptId }?.label,
            droppedLabel = inputs.firstOrNull { it.id == droppedId }?.label,
            reconciledCount = reconciledCount,
            blocked = blocked,
        )
    }

    fun mergeSelected(allowReconciled: Boolean) {
        val ids = _selection.value.toList()
        if (ids.size != 2) return
        viewModelScope.launch {
            try {
                session.write { com.sporez.actualist.core.commands.TransactionCommands(database, clock).merge(ids[0], ids[1], allowReconciled) }
                _selection.value = emptySet()
                messages.show("Merged 2 transactions")
            } catch (error: Exception) {
                messages.show(failure(error, "The transactions could not be merged."))
            }
        }
    }

    suspend fun categoryOptions(): List<com.sporez.actualist.android.ui.components.PickerOption> = session.read {
        ledger.categories(includeHidden = false).filter { !it.isIncome }.map { com.sporez.actualist.android.ui.components.PickerOption(it.id, it.name, it.groupName) }
    }

    fun setStatus(status: com.sporez.actualist.core.query.StatusFilter) {
        _query.value = _query.value.copy(status = if (_query.value.status == status) com.sporez.actualist.core.query.StatusFilter.ALL else status)
    }

    fun toggleUncategorized() {
        _query.value = _query.value.copy(uncategorizedOnly = !_query.value.uncategorizedOnly)
    }

    /** Cleared balance for the reconcile dialog, read fresh each time it opens. */
    suspend fun clearedBalance(): Long? = _query.value.accountId?.let { id -> session.read { com.sporez.actualist.core.transactions.Reconciliation(database, clock).snapshot(id).clearedBalance } }

    /** The balances the reconcile steps show, read fresh each time a step changes the ledger. */
    suspend fun reconcileInfo(): ReconcileInfo? {
        val id = _query.value.accountId ?: return null
        return session.read {
            val account = ledger.accounts(includeClosed = true).firstOrNull { it.id == id } ?: return@read null
            val snapshot = com.sporez.actualist.core.transactions.Reconciliation(database, clock).snapshot(id)
            ReconcileInfo(account.name, account.balance, snapshot.clearedBalance, snapshot.unclearedCount, snapshot.lastReconciled)
        }
    }

    /** Adds the cleared adjustment that closes the gap to [bankBalance]; false when it could not be added. */
    suspend fun addReconcileAdjustment(bankBalance: Long): Boolean {
        val accountId = _query.value.accountId ?: return false
        return try {
            session.write { com.sporez.actualist.core.transactions.Reconciliation(database, clock).createAdjustment(accountId, bankBalance, java.time.LocalDate.now()) }
            messages.show("Adjustment added")
            true
        } catch (error: Exception) {
            messages.show(error.message ?: "Reconciliation failed.")
            false
        }
    }

    /** Locks every cleared transaction once the difference is zero. */
    fun lockReconciled(bankBalance: Long) {
        val accountId = _query.value.accountId ?: return
        viewModelScope.launch {
            try {
                session.write { com.sporez.actualist.core.transactions.Reconciliation(database, clock).finish(accountId, bankBalance) }
                messages.show("Reconciled and locked")
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

    /** The account's group and the groups it can move to. */
    suspend fun groupChoices(): Pair<String?, List<com.sporez.actualist.core.accountgroups.AccountGroup>> {
        val id = _query.value.accountId ?: return null to emptyList()
        return session.read {
            val groups = com.sporez.actualist.core.accountgroups.AccountGroups(database, clock)
            groups.accountGroupIds()[id] to groups.groups()
        }
    }

    fun moveToGroup(groupId: String?) = accountWrite(if (groupId == null) "Removed from its group" else "Moved to the group") {
        com.sporez.actualist.core.accountgroups.AccountGroups(database, clock).moveAccount(it, groupId)
    }

    suspend fun accountNote(): String? {
        val id = _query.value.accountId ?: return null
        return session.read { com.sporez.actualist.core.notes.EntityNotes(database).read(com.sporez.actualist.core.notes.NoteTarget.Account(id)) }
    }

    fun saveAccountNote(text: String) = accountWrite("Notes saved") {
        com.sporez.actualist.core.notes.EntityNotes(database).write(com.sporez.actualist.core.notes.NoteTarget.Account(it), text)
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

    val hasSimpleFinBridge: Boolean get() = server?.hasSimpleFinBridge == true

    /** Connects SimpleFIN on this device when the server does not host it (Actualist's direct bridge). */
    suspend fun connectSimpleFinBridge(setupToken: String) {
        server?.connectSimpleFinBridge(setupToken)
    }

    fun disconnectSimpleFinBridge() {
        server?.disconnectSimpleFinBridge()
        viewModelScope.launch { messages.show("SimpleFIN disconnected from this device") }
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
    val latestPendingNew by androidx.compose.runtime.rememberUpdatedState((state as? Loadable.Ready)?.value?.pendingNewIds.orEmpty())
    androidx.compose.runtime.DisposableEffect(viewModel) {
        onDispose { viewModel.reviewShown(latestPendingNew) }
    }
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
                    androidx.compose.foundation.lazy.LazyRow(
                        Modifier.fillMaxWidth().testTag("status-filters"),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val statuses = listOf(
                            com.sporez.actualist.core.query.StatusFilter.UNCLEARED to "Uncleared",
                            com.sporez.actualist.core.query.StatusFilter.CLEARED to "Cleared",
                            com.sporez.actualist.core.query.StatusFilter.RECONCILED to "Reconciled",
                        )
                        items(statuses, key = { it.first.raw }) { (status, label) ->
                            FilterChip(
                                selected = query.status == status,
                                onClick = { viewModel.setStatus(status) },
                                label = { Text(label) },
                                modifier = Modifier.testTag("status-${status.raw}"),
                            )
                        }
                    }
                    FilterChipsRow(list, viewModel)
                    list.filterProblem?.let { ErrorBanner(it, Modifier.padding(horizontal = 14.dp)) }
                    list.account?.let { account -> AccountSummary(account) }
                    if (list.rows.isEmpty()) {
                        MessageBox("No transactions", if (query.search.isNullOrBlank()) "Add one with the + button." else "Nothing matches that search.")
                    } else {
                        val selected by viewModel.selection.collectAsState()
                        if (selected.isNotEmpty()) SelectionBar(selected.size, viewModel)
                        TransactionList(
                            list.rows,
                            showAccount = query.accountId == null,
                            selected = selected,
                            pendingNew = list.pendingNewIds,
                            onOpen = { row -> if (selected.isNotEmpty()) viewModel.toggleSelected(row.id) else onOpen(row) },
                            onLongPress = { row -> viewModel.toggleSelected(row.id) },
                        )
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
private fun TransactionList(
    rows: List<TransactionRow>,
    showAccount: Boolean,
    selected: Set<String>,
    pendingNew: Set<String>,
    onOpen: (TransactionRow) -> Unit,
    onLongPress: (TransactionRow) -> Unit,
) {
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
                        TransactionItem(row, showAccount, row.id in selected, row.id in pendingNew, onOpen, onLongPress)
                        if (index < dayRows.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionItem(
    row: TransactionRow,
    showAccount: Boolean,
    isSelected: Boolean,
    isNew: Boolean,
    onOpen: (TransactionRow) -> Unit,
    onLongPress: (TransactionRow) -> Unit,
) {
    val background = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
    Column(
        Modifier.fillMaxWidth()
            .background(background)
            .combinedClickable(onClick = { onOpen(row) }, onLongClick = { onLongPress(row) })
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("transaction-${row.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!row.cleared) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.outline, modifier = Modifier.size(7.dp)) {}
                Spacer(Modifier.width(6.dp))
            }
            Text(row.displayPayee.ifBlank { "No payee" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (isNew) {
                Text("New", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp).testTag("new-marker-${row.id}"))
            }
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
                AccountNoteField(viewModel, onDismiss)
                GroupRow(viewModel, onDismiss)
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
    val applied by viewModel.adhoc.collectAsState()
    var sheet by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var managing by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth().testTag("saved-filters"),
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = applied != null && active == null,
                onClick = { sheet = true },
                label = { Text(if (applied != null) "Filters (${applied?.conditions?.size ?: 0})" else "More Filters") },
                modifier = Modifier.testTag("filter-new"),
            )
        }
        items(list.savedFilters, key = { it.id }) { filter ->
            FilterChip(
                selected = active == filter.id,
                onClick = { viewModel.selectFilter(filter.id) },
                label = { Text(filter.name) },
                modifier = Modifier.testTag("filter-${filter.id}"),
            )
        }
        if (applied != null || active != null) {
            item { TextButton(onClick = viewModel::resetFilter, modifier = Modifier.testTag("filter-reset-chip")) { Text("Reset Filters") } }
        }
        if (list.savedFilters.isNotEmpty()) {
            item { TextButton(onClick = { managing = true }, modifier = Modifier.testTag("filter-manage")) { Text("Saved Filters") } }
        }
    }
    if (sheet) TransactionFilterSheet(viewModel, onDismiss = { sheet = false })
    if (managing) SavedFiltersDialog(list.savedFilters, active, applied != null, viewModel, onDismiss = { managing = false })
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
        // When the server does not host SimpleFIN, connect it on this device with a setup token.
        if (problem != null && !viewModel.hasSimpleFinBridge) {
            var setupToken by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
            androidx.compose.material3.OutlinedTextField(
                setupToken,
                { setupToken = it },
                label = { Text("SimpleFIN setup token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("simplefin-setup-token"),
            )
            TextButton(
                enabled = setupToken.isNotBlank(),
                onClick = {
                    scope.launch {
                        problem = try {
                            viewModel.connectSimpleFinBridge(setupToken)
                            remotes = viewModel.simpleFinAccounts()
                            null
                        } catch (error: Exception) {
                            error.message ?: "SimpleFIN could not be connected."
                        }
                    }
                },
                modifier = Modifier.testTag("simplefin-connect-device"),
            ) { Text("Connect SimpleFIN on this device") }
        }
        if (viewModel.hasSimpleFinBridge) {
            TextButton(onClick = { viewModel.disconnectSimpleFinBridge(); onDismiss() }, modifier = Modifier.testTag("simplefin-disconnect-device")) {
                Text("Disconnect SimpleFIN on this device")
            }
        }
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


@Composable
private fun GroupRow(viewModel: TransactionsViewModel, onDismiss: () -> Unit) {
    var choices by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Pair<String?, List<com.sporez.actualist.core.accountgroups.AccountGroup>>?>(null)
    }
    var picking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { choices = runCatching { viewModel.groupChoices() }.getOrNull() }
    val loaded = choices ?: return
    if (loaded.second.isEmpty()) return
    val current = loaded.second.firstOrNull { it.id == loaded.first }
    TextButton(onClick = { picking = true }, modifier = Modifier.testTag("account-group")) { Text("Group: ${current?.name ?: "None"}") }
    if (picking) {
        com.sporez.actualist.android.ui.components.PickerDialog(
            title = "Account group",
            options = listOf(com.sporez.actualist.android.ui.components.PickerOption(null, "No group")) +
                loaded.second.map { com.sporez.actualist.android.ui.components.PickerOption(it.id, it.name) },
            onPick = { option ->
                picking = false
                if (option.id != loaded.first) viewModel.moveToGroup(option.id)
                onDismiss()
            },
            onDismiss = { picking = false },
        )
    }
}


@Composable
private fun AccountNoteField(viewModel: TransactionsViewModel, onDismiss: () -> Unit) {
    var original by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var loaded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        original = runCatching { viewModel.accountNote() }.getOrNull()
        text = original.orEmpty()
        loaded = true
    }
    if (!loaded) return
    androidx.compose.material3.OutlinedTextField(text, { text = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth().testTag("account-notes"))
    if (text.trim() != original.orEmpty()) {
        TextButton(onClick = { viewModel.saveAccountNote(text); onDismiss() }, modifier = Modifier.testTag("account-notes-save")) { Text("Save notes") }
    }
}


/** Working balance with the cleared and uncleared parts (Actualist's account summary). */
@Composable
private fun AccountSummary(account: Account) {
    Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp).testTag("account-summary")) {
        Column(Modifier.weight(1f)) {
            Text("Working balance", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyText(account.balance, colored = true, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("account-balance"))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("Cleared", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyText(account.clearedBalance, modifier = Modifier.testTag("account-cleared"))
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 18.dp)) {
            Text("Uncleared", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyText(account.balance - account.clearedBalance, modifier = Modifier.testTag("account-uncleared"))
        }
    }
}

/**
 * Batch actions on the selected rows, as Actualist's multi-select offers. Every action opens a
 * before → after review first; nothing changes until the review is confirmed.
 */
@Composable
private fun SelectionBar(count: Int, viewModel: TransactionsViewModel) {
    var choosing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<com.sporez.actualist.android.ui.components.PickerOption>?>(null) }
    var review by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.sporez.actualist.core.transactions.BatchReview?>(null) }
    var categoryId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var mergeReview by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<MergeReviewState?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun open(intent: BatchIntent) {
        review = viewModel.batchReview(intent)
    }
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth().testTag("selection-bar"),
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item { Text("$count selected", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 6.dp)) }
        item { TextButton(onClick = { open(BatchIntent.CLEAR) }, modifier = Modifier.testTag("batch-clear")) { Text("Clear") } }
        item { TextButton(onClick = { open(BatchIntent.UNCLEAR) }, modifier = Modifier.testTag("batch-unclear")) { Text("Unclear") } }
        item {
            TextButton(onClick = { scope.launch { choosing = viewModel.categoryOptions() } }, modifier = Modifier.testTag("batch-categorize")) { Text("Categorize") }
        }
        item { TextButton(onClick = { open(BatchIntent.DUPLICATE) }, modifier = Modifier.testTag("batch-duplicate")) { Text("Duplicate") } }
        if (count == 2) {
            item { TextButton(onClick = { scope.launch { mergeReview = viewModel.mergeReview() } }, modifier = Modifier.testTag("batch-merge")) { Text("Merge") } }
        }
        item { TextButton(onClick = { open(BatchIntent.DELETE) }, modifier = Modifier.testTag("batch-delete")) { Text("Delete", color = MaterialTheme.colorScheme.error) } }
        item { TextButton(onClick = viewModel::clearSelection, modifier = Modifier.testTag("batch-cancel")) { Text("Cancel") } }
    }
    choosing?.let { options ->
        com.sporez.actualist.android.ui.components.PickerDialog(
            title = "Categorize as",
            options = options,
            searchable = true,
            onPick = { option ->
                choosing = null
                option.id?.let {
                    categoryId = it
                    review = viewModel.batchReview(BatchIntent.CATEGORIZE, option.title)
                }
            },
            onDismiss = { choosing = null },
        )
    }
    review?.let { current ->
        BatchReviewDialog(
            review = current,
            onDismiss = { review = null },
            onConfirm = {
                val unlock = current.needsReconciledConfirmation
                when (current.intent) {
                    BatchIntent.CLEAR -> viewModel.clearSelected(true, unlock)
                    BatchIntent.UNCLEAR -> viewModel.clearSelected(false, unlock)
                    BatchIntent.CATEGORIZE -> categoryId?.let { viewModel.categorizeSelected(it, unlock) }
                    BatchIntent.DELETE -> viewModel.deleteSelected(unlock)
                    BatchIntent.DUPLICATE -> viewModel.duplicateSelected()
                }
                review = null
            },
        )
    }
    mergeReview?.let { current ->
        MergeReviewDialog(
            review = current,
            onDismiss = { mergeReview = null },
            onConfirm = {
                viewModel.mergeSelected(allowReconciled = current.reconciledCount > 0)
                mergeReview = null
            },
        )
    }
}
