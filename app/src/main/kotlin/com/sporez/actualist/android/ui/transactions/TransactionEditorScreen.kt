@file:OptIn(ExperimentalMaterial3Api::class)

package com.sporez.actualist.android.ui.transactions

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.sporez.actualist.core.budget.Months
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

    /** Each category's available balance in the month of [date], for the category picker; null when it cannot be read. */
    suspend fun categoryBalances(date: LocalDate): Map<String, Long>? = try {
        session.read {
            budget.fetchBudgetMonth(Months.id(date.year, date.monthValue)).categoryGroups
                .flatMap { it.categories }
                .associate { it.id to it.balance }
        }
    } catch (error: Exception) {
        null
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

/**
 * One split line as the form holds it. A null [payeeId] with no [payeeName] stays on the transaction's
 * payee; a null [outflow] follows the transaction's direction (a line can go the other way).
 */
private data class SplitLine(
    val categoryId: String?,
    val amountText: String,
    val id: String? = null,
    val payeeId: String? = null,
    val payeeName: String = "",
    val notes: String = "",
    val outflow: Boolean? = null,
)

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
                // A link may carry only a direction; keep the field empty rather than 0.00.
                if (d.amountMinorUnits > 0) amountText = Money.format(d.amountMinorUnits, symbol = "", grouping = false)
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
    var categoryBalances by remember { mutableStateOf<Map<String, Long>?>(null) }
    val pickingCategory = picking == "category" || picking?.startsWith("split-") == true
    LaunchedEffect(pickingCategory, dateEpochDay) {
        if (pickingCategory) categoryBalances = viewModel.categoryBalances(LocalDate.ofEpochDay(dateEpochDay))
    }
    var splitting by rememberSaveable { mutableStateOf(existing?.isParent == true) }
    var splits by remember {
        mutableStateOf(
            existing?.takeIf { it.isParent }?.let { parent ->
                val parentOutflow = parent.amount <= 0
                parent.children.map { child ->
                    SplitLine(
                        categoryId = child.categoryId,
                        amountText = Money.format(kotlin.math.abs(child.amount), symbol = "", grouping = false),
                        id = child.id,
                        payeeId = child.payeeId.takeIf { it != parent.payeeId },
                        notes = child.notes ?: "",
                        outflow = (child.amount <= 0).takeIf { it != parentOutflow },
                    )
                }
            } ?: listOf(SplitLine(null, ""), SplitLine(null, "")),
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
    val splitAmounts = splits.map { line -> Money.parse(line.amountText)?.let { if (line.outflow ?: outflow) -kotlin.math.abs(it) else kotlin.math.abs(it) } }
    fun splitPayee(line: SplitLine): Payee? = loaded.payees.firstOrNull { it.id == line.payeeId }
    fun splitPayeeLabel(line: SplitLine): String =
        splitPayee(line)?.let { if (it.isTransfer) "Transfer: ${it.name}" else it.name } ?: line.payeeName.ifBlank { "Same as transaction" }
    val splitRemaining = (signed ?: 0) - splitAmounts.sumOf { it ?: 0 }
    val canSave = signed != null && account != null && (!splitting || (splitAmounts.all { it != null } && splitRemaining == 0L))
    val editingSplit = existing?.isChild == true
    var rulesApplied by remember { mutableStateOf<List<String>>(emptyList()) }

    var ruleDeletes by remember { mutableStateOf(false) }
    var ruleDeleteReview by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var discardPrompt by remember { mutableStateOf(false) }
    val editorScope = rememberCoroutineScope()
    fun ruleInput() = com.sporez.actualist.core.rules.RuleTransactionInput(
        accountId = accountId!!,
        date = LocalDate.ofEpochDay(dateEpochDay),
        amount = signed ?: 0,
        payeeId = payeeId,
        payeeName = payee?.name ?: newPayeeName,
        categoryId = categoryId,
        notes = notes.ifBlank { null },
        cleared = cleared,
        importedPayee = newPayeeName.ifBlank { null },
    )

    // New simple transactions get the rule preview whenever the payee or account changes.
    LaunchedEffect(payeeId, newPayeeName, accountId) {
        if (existing != null || splitting || isTransfer || accountId == null) return@LaunchedEffect
        if (payeeId == null && newPayeeName.isBlank()) return@LaunchedEffect
        val result = viewModel.previewRules(ruleInput()) ?: return@LaunchedEffect
        ruleDeletes = result.deletesTransaction
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

    // Unsaved edits: what the form holds now against what it held once it had loaded (and any shared text was applied).
    fun formSnapshot(): List<Any?> = listOf(
        outflow, amountText, accountId, payeeId, newPayeeName, categoryId, notes, cleared, dateEpochDay, splitting,
        splits.toList(),
    )
    var baseline by remember { mutableStateOf<List<Any?>?>(null) }
    LaunchedEffect(Unit) { baseline = formSnapshot() }
    val dirty = baseline.let { it != null && it != formSnapshot() }
    BackHandler(enabled = dirty) { discardPrompt = true }

    var reconciledScope by remember { mutableStateOf<com.sporez.actualist.core.transactions.ReconciledScope?>(null) }
    var reconcileGuard by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(existing?.id) { reconciledScope = viewModel.reconciledScope() }
    val reconciledNow = reconciledScope?.isEmpty == false
    val performSave: (Boolean) -> Unit = { unlock ->
                    if (existing != null && splitting) {
                        viewModel.saveSplit(
                            com.sporez.actualist.core.transactions.SplitFamilyEdit(
                                amount = signed!!,
                                splits = splits.mapIndexed { i, line ->
                                    com.sporez.actualist.core.transactions.SplitEdit(
                                        id = line.id,
                                        categoryId = if (splitPayee(line)?.isTransfer == true) null else line.categoryId,
                                        amount = splitAmounts[i]!!,
                                        notes = if (line.id == null) line.notes.ifBlank { null } else line.notes,
                                        payeeId = line.payeeId,
                                        payeeName = if (line.payeeId == null) line.payeeName else "",
                                    )
                                },
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
                        splits = if (splitting) {
                            splits.mapIndexed { i, line ->
                                SplitDraft(
                                    categoryId = if (splitPayee(line)?.isTransfer == true) null else line.categoryId,
                                    amount = splitAmounts[i]!!,
                                    notes = line.notes.ifBlank { null },
                                    payeeId = line.payeeId,
                                    payeeName = if (line.payeeId == null) line.payeeName else "",
                                )
                            }
                        } else {
                            emptyList()
                        },
                    )
                    viewModel.save(draft, unlock)
                    }
    }

    Column(Modifier.fillMaxSize().testTag("transaction-editor")) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (dirty) discardPrompt = true else onDone() }, modifier = Modifier.testTag("editor-close")) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            Text(if (existing == null) "New transaction" else "Edit transaction", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(
                enabled = canSave && !editingSplit,
                onClick = {
                    editorScope.launch {
                        // Save is blocked while a matching rule would delete the transaction (Actualist asks first).
                        val deletes = ruleDeletes || (
                            existing != null && !splitting && !isTransfer && accountId != null &&
                                viewModel.previewRules(ruleInput())?.deletesTransaction == true
                            )
                        if (deletes) {
                            ruleDeleteReview = true
                        } else if (reconciledNow) {
                            reconcileGuard = "save"
                        } else {
                            performSave(false)
                        }
                    }
                },
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
                            val lineOutflow = line.outflow ?: outflow
                            val lineIsTransfer = splitPayee(line)?.isTransfer == true
                            FieldButton("Split ${index + 1} payee", splitPayeeLabel(line), "split-payee-$index") { picking = "splitpayee-$index" }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { splits = splits.toMutableList().also { it[index] = line.copy(outflow = !lineOutflow) } },
                                    modifier = Modifier.testTag("split-sign-$index"),
                                ) { Text(if (lineOutflow) "− Outflow" else "+ Inflow") }
                                AmountField(
                                    line.amountText,
                                    { text -> splits = splits.toMutableList().also { it[index] = line.copy(amountText = text) } },
                                    label = "Amount",
                                    modifier = Modifier.weight(1f),
                                    tag = "split-amount-$index",
                                )
                            }
                            if (lineIsTransfer) {
                                FieldButton("Split ${index + 1} category", "Account Transfer", "split-category-$index") {}
                            } else {
                                FieldButton("Split ${index + 1} category", loaded.categories.firstOrNull { it.id == line.categoryId }?.name ?: "Uncategorized", "split-category-$index") { picking = "split-$index" }
                            }
                            androidx.compose.material3.OutlinedTextField(
                                line.notes,
                                { text -> splits = splits.toMutableList().also { it[index] = line.copy(notes = text) } },
                                label = { Text("Split ${index + 1} notes") },
                                modifier = Modifier.fillMaxWidth().testTag("split-notes-$index"),
                            )
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
                    val targetOutflow = splits[target].outflow ?: outflow
                    // A line keeps its direction; one that would have to flip to close the gap does not offer the fill.
                    val sameDirection = if (targetOutflow) filled <= 0 else filled >= 0
                    OutlinedButton(
                        enabled = sameDirection,
                        onClick = {
                            splits = splits.toMutableList().also {
                                it[target] = it[target].copy(amountText = Money.format(kotlin.math.abs(filled), symbol = "", grouping = false))
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
                    TextButton(onClick = { if (reconciledNow) reconcileGuard = "delete" else confirmDelete = true }, modifier = Modifier.testTag("editor-delete")) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            modifier = Modifier.testTag("delete-transaction-dialog"),
            title = { Text("Delete Transaction?") },
            text = { Text("Delete ${existing.displayPayee.ifBlank { "this transaction" }}?") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete() }, modifier = Modifier.testTag("delete-transaction-confirm")) {
                    Text("Delete Transaction", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }, modifier = Modifier.testTag("delete-transaction-cancel")) { Text("Cancel") } },
        )
    }
    if (discardPrompt) {
        AlertDialog(
            onDismissRequest = { discardPrompt = false },
            modifier = Modifier.testTag("discard-dialog"),
            title = { Text("Discard Changes") },
            text = { Text("Your edits to this transaction have not been saved.") },
            confirmButton = {
                TextButton(onClick = { discardPrompt = false; onDone() }, modifier = Modifier.testTag("discard-confirm")) {
                    Text("Discard Changes", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { discardPrompt = false }, modifier = Modifier.testTag("discard-keep")) { Text("Keep Editing") } },
        )
    }
    if (ruleDeleteReview) {
        AlertDialog(
            onDismissRequest = { ruleDeleteReview = false },
            modifier = Modifier.testTag("rule-delete-dialog"),
            title = { Text("A Rule Would Delete This Transaction") },
            text = {
                Text(
                    if (existing != null) {
                        "A matching rule wants to delete this transaction. Delete it, or keep the existing row and leave it unsaved."
                    } else {
                        "A matching rule would delete this transaction, so it will not be saved."
                    },
                )
            },
            confirmButton = {
                if (existing != null) {
                    TextButton(onClick = { ruleDeleteReview = false; viewModel.delete() }, modifier = Modifier.testTag("rule-delete-confirm")) {
                        Text("Delete Transaction", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { ruleDeleteReview = false }, modifier = Modifier.testTag("rule-delete-keep")) { Text("Keep Editing") } },
        )
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
        "category" -> CategoryPickerDialog(
            title = "Category",
            categories = loaded.categories,
            onPick = {
                categoryId = it
                picking = null
            },
            onDismiss = { picking = null },
            balances = categoryBalances,
            selectedId = categoryId,
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
        else -> if (target.startsWith("splitpayee-")) {
            val index = target.removePrefix("splitpayee-").toInt()
            PayeePickerDialog(
                payees = loaded.payees,
                recentIds = loaded.recentPayeeIds,
                currentAccountId = accountId,
                onPick = { id ->
                    splits = splits.toMutableList().also { it[index] = it[index].copy(payeeId = id, payeeName = "") }
                    picking = null
                },
                onCreate = { name ->
                    splits = splits.toMutableList().also { it[index] = it[index].copy(payeeId = null, payeeName = name) }
                    picking = null
                },
                onDismiss = { picking = null },
            )
        } else if (target.startsWith("split-")) {
            val index = target.removePrefix("split-").toInt()
            CategoryPickerDialog(
                title = "Split category",
                categories = loaded.categories,
                onPick = { id ->
                    splits = splits.toMutableList().also { it[index] = it[index].copy(categoryId = id) }
                    picking = null
                },
                onDismiss = { picking = null },
                balances = categoryBalances,
                selectedId = splits.getOrNull(index)?.categoryId,
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
