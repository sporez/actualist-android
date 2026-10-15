@file:OptIn(ExperimentalMaterial3Api::class)

package com.sporez.actualist.android.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sporez.actualist.android.ui.components.AmountField
import com.sporez.actualist.android.ui.components.ErrorBanner
import com.sporez.actualist.android.ui.components.LoadingBox
import com.sporez.actualist.android.ui.components.PickerDialog
import com.sporez.actualist.android.ui.components.PickerOption
import com.sporez.actualist.android.ui.components.LocalHideAmounts
import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.transactions.Account
import com.sporez.actualist.core.transactions.CategoryOption
import com.sporez.actualist.core.transactions.Payee
import com.sporez.actualist.core.transactions.SplitDraft
import com.sporez.actualist.core.transactions.SplitRemainingPresentation
import com.sporez.actualist.core.transactions.TransactionDraft
import com.sporez.actualist.core.transactions.TransactionEdit
import com.sporez.actualist.core.transactions.TransactionRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class EditorData(
    val accounts: List<Account>,
    val payees: List<Payee>,
    val categories: List<CategoryOption>,
    val existing: TransactionRow?,
    /** Payees of the newest transactions, most recent first (the picker's "Recent" section). */
    val recentPayeeIds: List<String> = emptyList(),
)

class TransactionEditorViewModel(
    private val session: BudgetSession,
    private val transactionId: String?,
    val defaultAccountId: String?,
) : ViewModel() {
    private val _data = MutableStateFlow<EditorData?>(null)
    val data: StateFlow<EditorData?> = _data.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _data.value = session.read {
                    EditorData(
                        accounts = ledger.accounts(includeClosed = false),
                        payees = ledger.payees(),
                        categories = ledger.categories(),
                        existing = transactionId?.let { ledger.transaction(it) },
                        recentPayeeIds = com.sporez.actualist.core.transactions.PayeeRecents.ids(
                            ledger.transactions(com.sporez.actualist.core.transactions.TransactionQuery(limit = 200)),
                        ),
                    )
                }
            } catch (error: Exception) {
                _error.value = error.message ?: "The editor could not load."
            }
        }
    }

    /**
     * Runs [block] with the transaction's reconciled rows unlocked (after the user confirmed
     * Actualist's warning) and locks the survivors again, since the writers refuse locked rows.
     */
    private fun BudgetSession.withUnlock(unlock: Boolean, block: () -> Unit) {
        val mutations = com.sporez.actualist.core.transactions.ReconciledMutations(database)
        val locked = if (unlock && transactionId != null) mutations.unlock(listOf(transactionId)) else emptyList()
        try {
            block()
        } finally {
            if (locked.isNotEmpty()) mutations.relock(locked)
        }
    }

    /** Which of this transaction's rows (and its transfer's other side) are reconciled. */
    suspend fun reconciledScope(): com.sporez.actualist.core.transactions.ReconciledScope? {
        val id = transactionId ?: return null
        return session.read { com.sporez.actualist.core.transactions.ReconciledMutations(database).scope(id) }
    }

    fun save(draft: TransactionDraft, unlockReconciled: Boolean = false) = launchWrite {
        if (transactionId == null) {
            session.write {
                val created = transactionWrites.create(draft)
                // Actual learns categories from the user's edits (transactions-batch-update with learnCategories).
                if (draft.categoryId != null) com.sporez.actualist.core.rules.CategoryLearning(database).learn(listOf(created.transactionId), java.time.LocalDate.now())
            }
        } else {
            val existing = data.value?.existing
            session.write {
                withUnlock(unlockReconciled) {
                transactionWrites.update(
                    transactionId,
                    TransactionEdit(
                        date = draft.date,
                        accountId = draft.accountId.takeIf { it != existing?.accountId },
                        amount = draft.amount,
                        payeeId = draft.payeeId.takeIf { it != existing?.payeeId },
                        payeeName = draft.payeeName.takeIf { draft.payeeId == null && it.isNotBlank() },
                        categoryId = draft.categoryId,
                        clearCategory = draft.categoryId == null && existing?.categoryId != null,
                        notes = draft.notes ?: "",
                        cleared = draft.cleared,
                    ),
                )
                if (draft.categoryId != null && draft.categoryId != existing?.categoryId) {
                    com.sporez.actualist.core.rules.CategoryLearning(database).learn(listOf(transactionId), java.time.LocalDate.now())
                }
                }
            }
        }
    }

    /**
     * Actualist's editor rule preview: runs the budget's rules on the draft as typed and
     * returns the result for the editor to show before saving. Null when rules cannot run.
     */
    suspend fun previewRules(input: com.sporez.actualist.core.rules.RuleTransactionInput): com.sporez.actualist.core.rules.RuleRunResult? = try {
        session.read { com.sporez.actualist.core.rules.RuleEngine(database).run(input, LocalDate.now()) }
    } catch (error: Exception) {
        null
    }

    fun saveSplit(edit: com.sporez.actualist.core.transactions.SplitFamilyEdit, unlockReconciled: Boolean = false) = launchWrite {
        session.write { withUnlock(unlockReconciled) { transactionWrites.updateSplit(transactionId!!, edit) } }
    }

    fun duplicate() = launchWrite {
        session.write { com.sporez.actualist.core.commands.TransactionCommands(database, clock).duplicate(listOf(transactionId!!)) }
    }

    /**
     * Turns this transaction into a schedule, as Actualist's "Make schedule" does: same account,
     * payee and (approximate) amount, repeating from the transaction's date.
     */
    fun makeSchedule(frequency: com.sporez.actualist.core.rules.ScheduleFrequency, name: String? = null) = launchWrite {
        val row = data.value?.existing ?: error("missing transaction")
        val start = row.date ?: error("This transaction has no date.")
        session.write {
            com.sporez.actualist.core.schedules.Schedules(database, clock).create(
                com.sporez.actualist.core.schedules.ScheduleCreateCommand(
                    identity = com.sporez.actualist.core.schedules.ScheduleCreateIdentity.make(),
                    name = (name ?: row.displayPayee).ifBlank { null },
                    definition = com.sporez.actualist.core.schedules.ScheduleDefinitionDraft(
                        accountId = row.accountId,
                        payeeMappingId = row.payeeId,
                        amount = com.sporez.actualist.core.schedules.ScheduleAmountDraft.Approximate(row.amount),
                        dateRule = com.sporez.actualist.core.schedules.ScheduleDateRule.Recurring(
                            com.sporez.actualist.core.rules.ScheduleRecurrence(start.toString(), frequency),
                            "isapprox",
                        ),
                    ),
                    postsTransaction = false,
                    customUpcomingLength = null,
                    asOfDayId = java.time.LocalDate.now().toString(),
                ),
            )
        }
    }

    /** Same-account, same-amount rows: the pairs Actual's merge is meant for. */
    suspend fun mergeCandidates(): List<TransactionRow> {
        val row = data.value?.existing ?: return emptyList()
        return session.read {
            ledger.transactions(com.sporez.actualist.core.transactions.TransactionQuery(accountId = row.accountId, limit = 2_000))
                .filter { it.id != row.id && it.amount == row.amount }
        }
    }

    /** Merges, asking first when reconciled rows are involved (as Actualist does). */
    fun merge(otherId: String, allowReconciled: Boolean) = launchWrite {
        session.write { com.sporez.actualist.core.commands.TransactionCommands(database, clock).merge(transactionId!!, otherId, allowReconciled) }
    }

    suspend fun mergeNeedsConfirmation(otherId: String): Boolean = session.read {
        com.sporez.actualist.core.commands.TransactionCommands(database, clock).previewMerge(transactionId!!, otherId).reconciledIds.isNotEmpty()
    }

    fun delete(unlockReconciled: Boolean = false) = launchWrite {
        session.write { withUnlock(unlockReconciled) { transactionWrites.delete(transactionId!!) } }
    }

    private fun launchWrite(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                _saved.value = true
            } catch (error: Exception) {
                _error.value = error.message ?: "The transaction could not be saved."
            }
        }
    }
}

private val dateLabel = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)

private class SplitLine(val categoryId: String?, val amountText: String, val id: String? = null)

@Composable
fun TransactionEditorScreen(viewModel: TransactionEditorViewModel, onDone: () -> Unit) {
    val data by viewModel.data.collectAsState()
    val error by viewModel.error.collectAsState()
    val saved by viewModel.saved.collectAsState()
    LaunchedEffect(saved) { if (saved) onDone() }
    val loaded = data
    if (loaded == null) {
        if (error != null) ErrorBanner(error!!, Modifier.padding(16.dp)) else LoadingBox()
        return
    }
    val existing = loaded.existing
    var outflow by rememberSaveable { mutableStateOf(existing?.let { it.amount <= 0 } ?: true) }
    var amountText by rememberSaveable { mutableStateOf(existing?.let { Money.format(kotlin.math.abs(it.amount), symbol = "", grouping = false) } ?: "") }
    var accountId by rememberSaveable { mutableStateOf(existing?.accountId ?: viewModel.defaultAccountId ?: loaded.accounts.firstOrNull { !it.offBudget }?.id ?: loaded.accounts.firstOrNull()?.id) }
    var payeeId by rememberSaveable { mutableStateOf(existing?.payeeId) }
    var newPayeeName by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf(existing?.categoryId) }
    var notes by rememberSaveable { mutableStateOf(existing?.notes ?: "") }
    var cleared by rememberSaveable { mutableStateOf(existing?.cleared ?: false) }
    var dateEpochDay by rememberSaveable { mutableStateOf((existing?.date ?: LocalDate.now()).toEpochDay()) }
    // Text shared into the app (Actualist's Import Transaction from Text) prefills a new transaction once.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (existing == null) {
            com.sporez.actualist.android.TextImportPrefill.take(loaded.accounts, loaded.payees, loaded.categories)?.let { d ->
                outflow = d.outflow
                amountText = Money.format(d.amountMinorUnits, symbol = "", grouping = false)
                d.accountId?.let { accountId = it }
                payeeId = d.payeeId
                newPayeeName = d.newPayeeName
                categoryId = d.categoryId
                if (d.notes.isNotBlank()) notes = d.notes
                d.date?.let { dateEpochDay = it.toEpochDay() }
            }
        }
    }
    var picking by remember { mutableStateOf<String?>(null) }
    var splitting by rememberSaveable { mutableStateOf(existing?.isParent == true) }
    var splits by remember {
        mutableStateOf(
            existing?.takeIf { it.isParent }?.children?.map { SplitLine(it.categoryId, Money.format(kotlin.math.abs(it.amount), symbol = "", grouping = false), it.id) }
                ?: listOf(SplitLine(null, ""), SplitLine(null, "")),
        )
    }

    val account = loaded.accounts.firstOrNull { it.id == accountId }
    val payee = loaded.payees.firstOrNull { it.id == payeeId }
    val isTransfer = payee?.isTransfer == true
    val offBudget = account?.offBudget == true
    val transferToOffBudget = isTransfer && loaded.accounts.firstOrNull { it.id == payee?.transferAccountId }?.offBudget == true
    val showCategory = !offBudget && (!isTransfer || transferToOffBudget) && !splitting
    val magnitude = Money.parse(amountText)
    val signed = magnitude?.let { if (outflow) -kotlin.math.abs(it) else kotlin.math.abs(it) }
    val splitAmounts = splits.map { line -> Money.parse(line.amountText)?.let { if (outflow) -kotlin.math.abs(it) else kotlin.math.abs(it) } }
    val splitRemaining = (signed ?: 0) - splitAmounts.sumOf { it ?: 0 }
    val canSave = signed != null && account != null && (!splitting || (splitAmounts.all { it != null } && splitRemaining == 0L))
    val editingSplit = existing?.isChild == true
    var rulesApplied by remember { mutableStateOf<List<String>>(emptyList()) }

    // New simple transactions get the rule preview whenever the payee or account changes.
    LaunchedEffect(payeeId, newPayeeName, accountId) {
        if (existing != null || splitting || isTransfer || accountId == null) return@LaunchedEffect
        if (payeeId == null && newPayeeName.isBlank()) return@LaunchedEffect
        val result = viewModel.previewRules(
            com.sporez.actualist.core.rules.RuleTransactionInput(
                accountId = accountId!!,
                date = LocalDate.ofEpochDay(dateEpochDay),
                amount = signed ?: 0,
                payeeId = payeeId,
                payeeName = payee?.name ?: newPayeeName,
                categoryId = categoryId,
                notes = notes.ifBlank { null },
                cleared = cleared,
                importedPayee = newPayeeName.ifBlank { null },
            ),
        ) ?: return@LaunchedEffect
        if (result.appliedRuleIds.isEmpty() || result.deletesTransaction) return@LaunchedEffect
        rulesApplied = result.appliedRuleIds
        if (result.splits.isNotEmpty()) {
            splitting = true
            splits = result.splits.map { SplitLine(it.categoryId, Money.format(kotlin.math.abs(it.amount), symbol = "", grouping = false)) }
        } else {
            categoryId = result.categoryId
        }
        notes = result.notes ?: ""
        cleared = result.cleared
        if (result.payeeId != null && loaded.payees.any { it.id == result.payeeId }) {
            payeeId = result.payeeId
            newPayeeName = ""
        }
        if (loaded.accounts.any { it.id == result.accountId }) accountId = result.accountId
        dateEpochDay = result.date.toEpochDay()
        if (signed != null && result.amount != signed) {
            outflow = result.amount <= 0
            amountText = Money.format(kotlin.math.abs(result.amount), symbol = "", grouping = false)
        }
    }

    var reconciledScope by remember { mutableStateOf<com.sporez.actualist.core.transactions.ReconciledScope?>(null) }
    var reconcileGuard by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(existing?.id) { reconciledScope = viewModel.reconciledScope() }
    val reconciledNow = reconciledScope?.isEmpty == false
    val performSave: (Boolean) -> Unit = { unlock ->
                    if (existing != null && splitting) {
                        viewModel.saveSplit(
                            com.sporez.actualist.core.transactions.SplitFamilyEdit(
                                amount = signed!!,
                                splits = splits.mapIndexed { i, line -> com.sporez.actualist.core.transactions.SplitEdit(line.id, line.categoryId, splitAmounts[i]!!) },
                                date = LocalDate.ofEpochDay(dateEpochDay),
                                payeeId = payeeId.takeIf { it != existing.payeeId },
                                payeeName = newPayeeName.takeIf { payeeId == null && it.isNotBlank() },
                                notes = notes,
                                cleared = cleared,
                            ),
                            unlock,
                        )
                    } else {
                    val draft = TransactionDraft(
                        accountId = accountId!!,
                        date = LocalDate.ofEpochDay(dateEpochDay),
                        amount = signed!!,
                        payeeId = payeeId,
                        payeeName = if (payeeId == null) newPayeeName else "",
                        categoryId = if (showCategory) categoryId else null,
                        notes = notes.ifBlank { null },
                        cleared = cleared,
                        isTransfer = isTransfer,
                        splits = if (splitting) splits.mapIndexed { i, line -> SplitDraft(line.categoryId, splitAmounts[i]!!) } else emptyList(),
                    )
                    viewModel.save(draft, unlock)
                    }
    }

    Column(Modifier.fillMaxSize().testTag("transaction-editor")) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone, modifier = Modifier.testTag("editor-close")) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            Text(if (existing == null) "New transaction" else "Edit transaction", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(
                enabled = canSave && !editingSplit,
                onClick = { if (reconciledNow) reconcileGuard = "save" else performSave(false) },
                modifier = Modifier.testTag("editor-save"),
            ) { Text("Save") }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { ErrorBanner(it, Modifier.testTag("editor-error")) }
            if (editingSplit) ErrorBanner("This is part of a split. Open the whole transaction to edit it.")
            if (reconciledNow) ErrorBanner("This transaction is reconciled. Changing it asks you to confirm first.")
            if (rulesApplied.isNotEmpty()) {
                Text(
                    "Rules applied: ${rulesApplied.size}. Review before saving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("rules-applied"),
                )
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = outflow, onClick = { outflow = true }, shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.testTag("editor-outflow")) { Text("Outflow") }
                SegmentedButton(selected = !outflow, onClick = { outflow = false }, shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.testTag("editor-inflow")) { Text("Inflow") }
            }
            AmountField(amountText, { amountText = it }, label = "Amount", tag = "editor-amount")
            FieldButton("Payee", payee?.let { if (it.isTransfer) "Transfer: ${it.name}" else it.name } ?: newPayeeName.ifBlank { "None" }, "editor-payee") { picking = "payee" }
            FieldButton("Account", account?.name ?: "Choose", "editor-account") { picking = "account" }
            if (showCategory) {
                FieldButton("Category", loaded.categories.firstOrNull { it.id == categoryId }?.name ?: "Uncategorized", "editor-category") { picking = "category" }
            }
            FieldButton("Date", LocalDate.ofEpochDay(dateEpochDay).format(dateLabel), "editor-date") { picking = "date" }
            androidx.compose.material3.OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth().testTag("editor-notes"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = cleared, onCheckedChange = { cleared = it }, modifier = Modifier.testTag("editor-cleared"))
                Text("Cleared")
            }
            if (!isTransfer && !offBudget && existing?.isParent != true && existing?.isTransfer != true) {
                OutlinedButton(onClick = { splitting = !splitting }, modifier = Modifier.fillMaxWidth().testTag("editor-split-toggle")) {
                    Text(if (splitting) "Don't split" else "Split across categories")
                }
            }
            if (splitting) {
                splits.forEachIndexed { index, line ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FieldButton("Split ${index + 1} category", loaded.categories.firstOrNull { it.id == line.categoryId }?.name ?: "Uncategorized", "split-category-$index") { picking = "split-$index" }
                            AmountField(line.amountText, { text -> splits = splits.toMutableList().also { it[index] = SplitLine(line.categoryId, text, line.id) } }, label = "Amount", tag = "split-amount-$index")
                            if (splits.size > 2) {
                                TextButton(onClick = { splits = splits.toMutableList().also { it.removeAt(index) } }, modifier = Modifier.testTag("split-delete-$index")) {
                                    Text("Remove split ${index + 1}", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { splits = splits + SplitLine(null, "") }, modifier = Modifier.testTag("split-add")) { Text("Add split") }
                    val hideAmounts = LocalHideAmounts.current
                    val displayed = SplitRemainingPresentation.displayedCents(splitRemaining, signed ?: 0L)
                    Text(
                        SplitRemainingPresentation.statusText(displayed) { amount -> if (hideAmounts) "•••••" else Money.format(amount) },
                        color = if (splitRemaining == 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("split-remaining"),
                    )
                }
                if (signed != null && splitRemaining != 0L) {
                    // The first empty entry takes the remainder; with none empty, the last entry grows or shrinks by it.
                    val target = splits.indexOfFirst { it.amountText.isBlank() }.takeIf { it >= 0 } ?: splits.lastIndex
                    val filled = (splitAmounts[target] ?: 0L) + splitRemaining
                    val sameDirection = if (outflow) filled <= 0 else filled >= 0
                    OutlinedButton(
                        enabled = sameDirection,
                        onClick = {
                            splits = splits.toMutableList().also {
                                it[target] = SplitLine(it[target].categoryId, Money.format(kotlin.math.abs(filled), symbol = "", grouping = false), it[target].id)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("split-fill-remaining"),
                    ) { Text("Fill Remaining") }
                }
            }
            if (existing != null) {
                Row {
                    TextButton(onClick = { viewModel.duplicate() }, modifier = Modifier.testTag("editor-duplicate")) { Text("Duplicate") }
                    TextButton(onClick = { picking = "merge" }, modifier = Modifier.testTag("editor-merge")) { Text("Merge with…") }
                    if (!existing.isParent) TextButton(onClick = { picking = "schedule" }, modifier = Modifier.testTag("editor-make-schedule")) { Text("Schedule…") }
                    TextButton(onClick = { if (reconciledNow) reconcileGuard = "delete" else viewModel.delete() }, modifier = Modifier.testTag("editor-delete")) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    reconcileGuard?.let { action ->
        val scope = reconciledScope
        ReconciledGuardDialog(
            presentation = com.sporez.actualist.core.transactions.ReconciledMutationPresentation.make(
                intent = if (action == "delete") com.sporez.actualist.core.transactions.ReconciledIntent.DELETE else com.sporez.actualist.core.transactions.ReconciledIntent.UPDATE,
                targetReconciledCount = scope?.targetIds?.size ?: 1,
                includesPairedTransfer = scope?.pairedIds?.isNotEmpty() == true,
                targetRequiresUnlock = scope?.targetIds?.isNotEmpty() == true,
            ),
            onConfirm = {
                reconcileGuard = null
                if (action == "delete") viewModel.delete(unlockReconciled = true) else performSave(true)
            },
            onDismiss = { reconcileGuard = null },
        )
    }

    when (val target = picking) {
        "payee" -> PayeePickerDialog(
            payees = loaded.payees,
            recentIds = loaded.recentPayeeIds,
            currentAccountId = accountId,
            onPick = { id ->
                payeeId = id
                newPayeeName = ""
                picking = null
            },
            onCreate = { name ->
                payeeId = null
                newPayeeName = name
                picking = null
            },
            onDismiss = { picking = null },
        )
        "account" -> PickerDialog(
            title = "Account",
            options = loaded.accounts.map { PickerOption(it.id, it.name, if (it.offBudget) "Off budget" else "On budget") },
            onPick = {
                accountId = it.id
                picking = null
            },
            onDismiss = { picking = null },
        )
        "category" -> PickerDialog(
            title = "Category",
            options = listOf(PickerOption(null, "Uncategorized")) + loaded.categories.map { PickerOption(it.id, it.name, it.groupName) },
            searchable = true,
            onPick = {
                categoryId = it.id
                picking = null
            },
            onDismiss = { picking = null },
        )
        "date" -> {
            val state = rememberDatePickerState(initialSelectedDateMillis = LocalDate.ofEpochDay(dateEpochDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { dateEpochDay = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }
                        picking = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
            ) { DatePicker(state = state) }
        }
        "schedule" -> existing?.let { row ->
            ScheduleConversionReviewDialog(
                defaultName = row.displayPayee,
                amount = row.amount,
                accountName = row.accountName,
                payeeName = row.displayPayee,
                start = row.date ?: LocalDate.now(),
                onConfirm = { name, frequency ->
                    picking = null
                    viewModel.makeSchedule(frequency, name)
                },
                onDismiss = { picking = null },
            )
        }
        "merge" -> {
            var candidates by remember { mutableStateOf<List<TransactionRow>?>(null) }
            var confirmFor by remember { mutableStateOf<String?>(null) }
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            LaunchedEffect(Unit) { candidates = viewModel.mergeCandidates() }
            val list = candidates
            if (list != null && confirmFor == null) {
                PickerDialog(
                    title = if (list.isEmpty()) "No transaction with the same amount in this account" else "Merge with",
                    options = list.map { PickerOption(it.id, it.displayPayee.ifBlank { "No payee" }, listOfNotNull(it.date?.format(dateLabel), it.categoryName, it.notes).joinToString(" · ")) },
                    onPick = { option ->
                        option.id?.let { other ->
                            scope.launch {
                                if (viewModel.mergeNeedsConfirmation(other)) {
                                    confirmFor = other
                                } else {
                                    viewModel.merge(other, allowReconciled = false)
                                    picking = null
                                }
                            }
                        }
                    },
                    onDismiss = { picking = null },
                )
            }
            confirmFor?.let { other ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmFor = null; picking = null },
                    title = { Text("Merge reconciled transactions?") },
                    text = { Text("One of these transactions is reconciled. Merging changes reconciled history.") },
                    confirmButton = { TextButton(onClick = { viewModel.merge(other, allowReconciled = true); confirmFor = null; picking = null }) { Text("Merge") } },
                    dismissButton = { TextButton(onClick = { confirmFor = null; picking = null }) { Text("Cancel") } },
                )
            }
        }
        null -> {}
        else -> if (target.startsWith("split-")) {
            val index = target.removePrefix("split-").toInt()
            PickerDialog(
                title = "Split category",
                options = listOf(PickerOption(null, "Uncategorized")) + loaded.categories.map { PickerOption(it.id, it.name, it.groupName) },
                searchable = true,
                onPick = { option ->
                    splits = splits.toMutableList().also { it[index] = SplitLine(option.id, it[index].amountText, it[index].id) }
                    picking = null
                },
                onDismiss = { picking = null },
            )
        }
    }
}

@Composable
private fun FieldButton(label: String, value: String, tag: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
