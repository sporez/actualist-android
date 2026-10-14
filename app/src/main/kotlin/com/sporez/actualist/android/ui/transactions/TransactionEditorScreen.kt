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
import com.sporez.actualist.android.ui.components.money
import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.transactions.Account
import com.sporez.actualist.core.transactions.CategoryOption
import com.sporez.actualist.core.transactions.Payee
import com.sporez.actualist.core.transactions.SplitDraft
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
                    )
                }
            } catch (error: Exception) {
                _error.value = error.message ?: "The editor could not load."
            }
        }
    }

    fun save(draft: TransactionDraft) = launchWrite {
        if (transactionId == null) {
            session.write { transactionWrites.create(draft) }
        } else {
            val existing = data.value?.existing
            session.write {
                transactionWrites.update(
                    transactionId,
                    TransactionEdit(
                        date = draft.date,
                        amount = draft.amount,
                        payeeId = draft.payeeId.takeIf { it != existing?.payeeId },
                        payeeName = draft.payeeName.takeIf { draft.payeeId == null && it.isNotBlank() },
                        categoryId = draft.categoryId,
                        clearCategory = draft.categoryId == null && existing?.categoryId != null,
                        notes = draft.notes ?: "",
                        cleared = draft.cleared,
                    ),
                )
            }
        }
    }

    fun delete() = launchWrite { session.write { transactionWrites.delete(transactionId!!) } }

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

private class SplitLine(val categoryId: String?, val amountText: String)

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
    var picking by remember { mutableStateOf<String?>(null) }
    var splitting by rememberSaveable { mutableStateOf(false) }
    var splits by remember { mutableStateOf(listOf(SplitLine(null, ""), SplitLine(null, ""))) }

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
    val editingSplit = existing?.isParent == true || existing?.isChild == true

    Column(Modifier.fillMaxSize().testTag("transaction-editor")) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone, modifier = Modifier.testTag("editor-close")) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            Text(if (existing == null) "New transaction" else "Edit transaction", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(
                enabled = canSave && !editingSplit,
                onClick = {
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
                    viewModel.save(draft)
                },
                modifier = Modifier.testTag("editor-save"),
            ) { Text("Save") }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { ErrorBanner(it, Modifier.testTag("editor-error")) }
            if (editingSplit) ErrorBanner("Editing split transactions is not supported yet. You can delete and re-create it.")
            if (existing?.reconciled == true) ErrorBanner("This transaction is reconciled and locked.")
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
            if (existing == null && !isTransfer && !offBudget) {
                OutlinedButton(onClick = { splitting = !splitting }, modifier = Modifier.fillMaxWidth().testTag("editor-split-toggle")) {
                    Text(if (splitting) "Don't split" else "Split across categories")
                }
            }
            if (splitting) {
                splits.forEachIndexed { index, line ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FieldButton("Split ${index + 1} category", loaded.categories.firstOrNull { it.id == line.categoryId }?.name ?: "Uncategorized", "split-category-$index") { picking = "split-$index" }
                            AmountField(line.amountText, { text -> splits = splits.toMutableList().also { it[index] = SplitLine(line.categoryId, text) } }, label = "Amount", tag = "split-amount-$index")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { splits = splits + SplitLine(null, "") }, modifier = Modifier.testTag("split-add")) { Text("Add split") }
                    Text(
                        if (splitRemaining == 0L) "Fully allocated" else "${money(kotlin.math.abs(splitRemaining))} left to allocate",
                        color = if (splitRemaining == 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("split-remaining"),
                    )
                }
            }
            if (existing != null) {
                TextButton(onClick = { viewModel.delete() }, modifier = Modifier.testTag("editor-delete")) {
                    Text("Delete transaction", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    when (val target = picking) {
        "payee" -> PickerDialog(
            title = "Payee",
            options = listOf(PickerOption(null, "No payee")) + loaded.payees.filter { !it.isTransfer || it.transferAccountId != accountId }.map {
                PickerOption(it.id, if (it.isTransfer) "Transfer: ${it.name}" else it.name, if (it.isTransfer) "Move money between accounts" else null)
            },
            searchable = true,
            onPick = {
                payeeId = it.id
                newPayeeName = ""
                picking = null
            },
            onDismiss = { picking = null },
            extraAction = "Create payee" to { name: String ->
                payeeId = null
                newPayeeName = name
                picking = null
            },
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
        null -> {}
        else -> if (target.startsWith("split-")) {
            val index = target.removePrefix("split-").toInt()
            PickerDialog(
                title = "Split category",
                options = listOf(PickerOption(null, "Uncategorized")) + loaded.categories.map { PickerOption(it.id, it.name, it.groupName) },
                searchable = true,
                onPick = { option ->
                    splits = splits.toMutableList().also { it[index] = SplitLine(option.id, it[index].amountText) }
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
