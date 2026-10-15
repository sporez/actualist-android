package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sporez.actualist.android.ui.Loadable
import com.sporez.actualist.android.ui.MessageState
import com.sporez.actualist.android.ui.components.ErrorBanner
import com.sporez.actualist.android.ui.components.LoadingBox
import com.sporez.actualist.android.ui.components.PickerDialog
import com.sporez.actualist.android.ui.components.PickerOption
import com.sporez.actualist.android.ui.reloading
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.transactions.PayeeEntry
import com.sporez.actualist.core.transactions.PayeeOverview
import com.sporez.actualist.core.rules.CategoryLearning
import com.sporez.actualist.core.transactions.PayeeWrites
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class PayeesViewModel(private val session: BudgetSession) : ViewModel() {
    val messages = MessageState()
    val query = MutableStateFlow("")
    val payees = reloading(session, MutableStateFlow(Unit)) { PayeeWrites(database).overview() }

    /** Every live rule, so each payee can list the ones that refer to it. */
    val rules = reloading(session, MutableStateFlow(Unit)) { ruleSummaries() }

    /** Budget-wide learning switch and the payees with learning off (Actual's category learning). */
    val learning = reloading(session, MutableStateFlow(Unit)) {
        val learning = CategoryLearning(database)
        learning.enabled() to learning.payeesNotLearning()
    }

    fun setLearning(enabled: Boolean) = write(if (enabled) "Category learning on" else "Category learning off") {
        CategoryLearning(database).setEnabled(enabled)
    }

    fun setPayeeLearns(id: String, learns: Boolean) = write("Saved") { CategoryLearning(database).setPayeeLearns(id, learns) }

    fun setFavorite(id: String, favorite: Boolean) = write("Saved") { PayeeWrites(database).setFavorite(id, favorite) }

    fun create(name: String) = write("Payee added") { PayeeWrites(database).create(name) }

    fun delete(id: String) = write("Payee deleted") { PayeeWrites(database).delete(id) }

    fun rename(id: String, name: String) = write("Renamed") { PayeeWrites(database).rename(id, name) }

    fun merge(source: String, target: String) = write("Merged") { PayeeWrites(database).merge(setOf(source), target) }

    /** True after a batch action, until it is undone: the screen offers Undo for it. */
    val undoable = MutableStateFlow(false)

    /** Favorites or unfavorites every payee in [ids] in one session write, so one undo reverts them all. */
    fun favoriteAll(ids: Set<String>, favorite: Boolean) = write(
        if (favorite) "Favorited ${ids.size} payees" else "Unfavorited ${ids.size} payees",
        batch = true,
    ) {
        val writes = PayeeWrites(database)
        ids.forEach { writes.setFavorite(it, favorite) }
    }

    /** Switches category learning for every payee in [ids] in one session write. */
    fun learnAll(ids: Set<String>, learns: Boolean) = write(
        if (learns) "Learning on for ${ids.size} payees" else "Learning off for ${ids.size} payees",
        batch = true,
    ) {
        val learning = CategoryLearning(database)
        ids.forEach { learning.setPayeeLearns(it, learns) }
    }

    /** Merges every payee in [ids] except [keep] into [keep], in one session write. */
    fun mergeAll(ids: Set<String>, keep: String) = write("Merged ${ids.size - 1} into one payee", batch = true) {
        PayeeWrites(database).merge(ids - keep, keep)
    }

    /** Deletes every payee in [ids] (the caller checked they are unused), in one session write. */
    fun deleteAll(ids: Set<String>) = write("Deleted ${ids.size} payees", batch = true) {
        val writes = PayeeWrites(database)
        ids.forEach { writes.delete(it) }
    }

    /** Undoes the latest recorded write (the batch just made). */
    fun undoLast() {
        viewModelScope.launch {
            try {
                val undone = session.undo()
                undoable.value = false
                messages.show(if (undone) "Undone" else "Nothing to undo")
            } catch (error: Exception) {
                messages.show(error.message ?: "The change could not be undone.")
            }
        }
    }

    private fun write(done: String, batch: Boolean = false, block: BudgetSession.() -> Any?) {
        viewModelScope.launch {
            try {
                session.write { block() }
                undoable.value = batch
                messages.show(done)
            } catch (error: Exception) {
                messages.show(error.message ?: "The change could not be saved.")
            }
        }
    }
}

private fun PayeeEntry.statusText(): String {
    if (isTransfer) return "Managed by account"
    val parts = mutableListOf(if (transactionCount == 0) "Unused" else "$transactionCount ${if (transactionCount == 1) "transaction" else "transactions"}")
    if (ruleCount > 0) parts += "$ruleCount ${if (ruleCount == 1) "rule" else "rules"}"
    return parts.joinToString(" • ")
}

private fun deleteBlockedReason(entry: PayeeEntry, overview: PayeeOverview): String = when {
    entry.transactionCount > 0 -> "This payee is in use. Merge it into another payee before deleting it."
    entry.ruleCount > 0 -> "This payee is referenced by a rule and cannot be deleted."
    overview.unreadableRules -> "Deletion is disabled because one or more rules could not be inspected."
    else -> "This payee cannot be deleted."
}

/**
 * Payee management as Actualist's payees page: search, All/Unused, add, rename, favorite,
 * learning, merge, delete, and each payee's rules. [onEditRule] opens the rule editor for a
 * rule id, or for a new rule seeded with the payee when the id is null.
 */
@Composable
fun PayeesScreen(viewModel: PayeesViewModel, onEditRule: (ruleId: String?, payeeId: String) -> Unit = { _, _ -> }) {
    val state by viewModel.payees.collectAsState()
    val message by viewModel.messages.message.collectAsState()
    val learningState by viewModel.learning.collectAsState()
    val rulesState by viewModel.rules.collectAsState()
    val query by viewModel.query.collectAsState()
    val learning = (learningState as? Loadable.Ready)?.value
    val allRules = (rulesState as? Loadable.Ready)?.value ?: emptyList()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var merging by remember { mutableStateOf<PayeeEntry?>(null) }
    var showingRulesFor by remember { mutableStateOf<PayeeEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var unusedOnly by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<PayeeEntry?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchMerging by remember { mutableStateOf(false) }
    var batchDeleting by remember { mutableStateOf(false) }
    val undoable by viewModel.undoable.collectAsState()
    when (val current = state) {
        Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBanner(current.message, Modifier.padding(16.dp))
        is Loadable.Ready -> {
            val overview = current.value
            val unusedCount = overview.payees.count { it.isUnused }
            val needle = query.trim()
            val matching = overview.payees.filter {
                (needle.isEmpty() || it.name.contains(needle, ignoreCase = true) || (it.isTransfer && "Transfer: ${it.name}".contains(needle, ignoreCase = true))) &&
                    (!unusedOnly || it.isUnused)
            }
            val regular = matching.filter { !it.isTransfer }
            val transfers = matching.filter { it.isTransfer }
            LazyColumn(Modifier.fillMaxSize().testTag("payees-screen"), contentPadding = PaddingValues(14.dp)) {
                message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp).testTag("payees-message")) } }
                if (overview.unreadableRules) {
                    item { Text("One or more rules could not be inspected. Deleting payees is disabled to protect rule references.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
                }
                learning?.let { (enabled, _) ->
                    item {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Learn categories", style = MaterialTheme.typography.bodyLarge)
                                Text("Create payee rules from how you categorize, as Actual does", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = enabled, onCheckedChange = viewModel::setLearning, modifier = Modifier.testTag("learn-categories"))
                        }
                        HorizontalDivider()
                    }
                }
                item {
                    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(query, { viewModel.query.value = it }, label = { Text("Search payees") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("payees-search"))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !unusedOnly, onClick = { unusedOnly = false }, label = { Text("All") }, modifier = Modifier.testTag("payees-filter-all"))
                            if (unusedCount > 0 || unusedOnly) {
                                FilterChip(selected = unusedOnly, onClick = { unusedOnly = true }, label = { Text("Unused ($unusedCount)") }, modifier = Modifier.testTag("payees-filter-unused"))
                            }
                            Row(Modifier.weight(1f)) {}
                            OutlinedButton(
                                onClick = { selecting = !selecting; picked = emptySet() },
                                modifier = Modifier.testTag("payees-select"),
                            ) { Text(if (selecting) "Done" else "Select") }
                            if (undoable && !selecting) {
                                TextButton(onClick = viewModel::undoLast, modifier = Modifier.testTag("payees-undo")) { Text("Undo") }
                            }
                            OutlinedButton(onClick = { adding = true }, modifier = Modifier.testTag("payee-add")) { Text("Add payee") }
                        }
                    }
                }
                if (selecting) {
                    item {
                        val chosen = regular.filter { it.id in picked }
                        val chosenIds = chosen.map { it.id }.toSet()
                        Column(Modifier.padding(bottom = 8.dp).testTag("payees-batch-bar")) {
                            Text("${chosen.size} selected", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("payees-selected-count"))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = { picked = if (chosen.size == regular.size) emptySet() else regular.map { it.id }.toSet() },
                                    modifier = Modifier.testTag("payees-select-all"),
                                ) { Text(if (chosen.size == regular.size && regular.isNotEmpty()) "Deselect all" else "Select all") }
                                TextButton(onClick = { viewModel.favoriteAll(chosenIds, true); picked = emptySet() }, enabled = chosen.isNotEmpty(), modifier = Modifier.testTag("payees-batch-favorite")) { Text("Favorite") }
                                TextButton(onClick = { viewModel.favoriteAll(chosenIds, false); picked = emptySet() }, enabled = chosen.isNotEmpty(), modifier = Modifier.testTag("payees-batch-unfavorite")) { Text("Unfavorite") }
                                if (learning?.first == true) {
                                    TextButton(onClick = { viewModel.learnAll(chosenIds, true); picked = emptySet() }, enabled = chosen.isNotEmpty(), modifier = Modifier.testTag("payees-batch-learning-on")) { Text("Learning on") }
                                    TextButton(onClick = { viewModel.learnAll(chosenIds, false); picked = emptySet() }, enabled = chosen.isNotEmpty(), modifier = Modifier.testTag("payees-batch-learning-off")) { Text("Learning off") }
                                }
                                TextButton(onClick = { batchMerging = true }, enabled = chosen.size >= 2, modifier = Modifier.testTag("payees-batch-merge")) { Text("Merge") }
                                TextButton(
                                    onClick = { batchDeleting = true },
                                    enabled = chosen.isNotEmpty() && chosen.all { overview.canDelete(it) },
                                    modifier = Modifier.testTag("payees-batch-delete"),
                                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                            }
                            if (chosen.isNotEmpty() && !chosen.all { overview.canDelete(it) }) {
                                Text("Only unused payees that no rule refers to can be deleted. Merge the others instead.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (regular.isEmpty()) {
                    item { Text(if (needle.isEmpty()) "No payees" else "No matching payees", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp).testTag("payees-empty")) }
                }
                items(regular, key = { it.id }) { payee ->
                    Row(Modifier.fillMaxWidth().clickable {
                        if (selecting) picked = if (payee.id in picked) picked - payee.id else picked + payee.id else selectedId = payee.id
                    }.padding(vertical = 12.dp).testTag("payee-${payee.id}"), verticalAlignment = Alignment.CenterVertically) {
                        if (selecting) {
                            Checkbox(checked = payee.id in picked, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp).testTag("payee-check-${payee.id}"))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(buildString {
                                append(payee.name)
                                if (payee.favorite) append("  ★")
                                if (!payee.learnsCategories) append("  (learning off)")
                            })
                            Text(payee.statusText(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                }
                if (transfers.isNotEmpty()) {
                    item {
                        Text("Transfer payees", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                        Text("Transfer payees are linked to accounts and cannot be edited here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(transfers, key = { it.id }) { payee ->
                        Row(Modifier.fillMaxWidth().clickable { showingRulesFor = payee }.padding(vertical = 12.dp).testTag("payee-${payee.id}"), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Transfer: ${payee.name}")
                                Text(payee.statusText(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            val selected = overview.payees.firstOrNull { it.id == selectedId && !it.isTransfer }
            selected?.let { payee ->
                var name by remember(payee.id) { mutableStateOf(payee.name) }
                AlertDialog(
                    onDismissRequest = { selectedId = null },
                    title = { Text(payee.name) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("payee-name"))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Favorite", Modifier.weight(1f))
                                Switch(checked = payee.favorite, onCheckedChange = { viewModel.setFavorite(payee.id, it) }, modifier = Modifier.testTag("payee-favorite"))
                            }
                            learning?.let { (enabled, off) ->
                                if (enabled) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Learn this payee's category", Modifier.weight(1f))
                                        Switch(
                                            checked = payee.id !in off,
                                            onCheckedChange = { viewModel.setPayeeLearns(payee.id, it); selectedId = null },
                                            modifier = Modifier.testTag("payee-learns"),
                                        )
                                    }
                                }
                            }
                            Text("${payee.transactionCount} transactions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = { showingRulesFor = payee; selectedId = null }, modifier = Modifier.fillMaxWidth().testTag("payee-rules")) { Text("Rules (${payee.ruleCount})") }
                            OutlinedButton(onClick = { merging = payee; selectedId = null }, modifier = Modifier.fillMaxWidth().testTag("payee-merge")) { Text("Merge into another payee…") }
                            if (overview.canDelete(payee)) {
                                TextButton(onClick = { confirmDelete = payee; selectedId = null }, modifier = Modifier.testTag("payee-delete")) { Text("Delete payee", color = MaterialTheme.colorScheme.error) }
                            } else {
                                Text(deleteBlockedReason(payee, overview), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("payee-delete-blocked"))
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { viewModel.rename(payee.id, name); selectedId = null }, enabled = name.isNotBlank() && name.trim() != payee.name, modifier = Modifier.testTag("payee-rename")) { Text("Rename") } },
                    dismissButton = { TextButton(onClick = { selectedId = null }) { Text("Cancel") } },
                )
            }
            if (batchMerging) {
                val chosen = overview.payees.filter { it.id in picked && !it.isTransfer }
                PickerDialog(
                    title = "Payee to keep",
                    options = chosen.map { PickerOption(it.id, it.name, "${it.transactionCount} transactions") },
                    searchable = false,
                    onPick = { option ->
                        option.id?.let { viewModel.mergeAll(chosen.map { c -> c.id }.toSet(), it) }
                        batchMerging = false
                        picked = emptySet()
                    },
                    onDismiss = { batchMerging = false },
                )
            }
            if (batchDeleting) {
                val ids = picked
                AlertDialog(
                    onDismissRequest = { batchDeleting = false },
                    title = { Text("Delete ${ids.size} payees?") },
                    text = { Text("These payees are unused and not referenced by any rule. They will be removed; Undo reverts this.") },
                    confirmButton = {
                        TextButton(onClick = { viewModel.deleteAll(ids); batchDeleting = false; picked = emptySet() }, modifier = Modifier.testTag("payees-batch-delete-confirm")) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick = { batchDeleting = false }) { Text("Cancel") } },
                )
            }
            merging?.let { source ->
                val options = overview.payees.filter { it.id != source.id && !it.isTransfer }.map { PickerOption(it.id, it.name, "${it.transactionCount} transactions") }
                PickerDialog(
                    title = "Merge ${source.name} into",
                    options = options,
                    searchable = true,
                    onPick = { option ->
                        option.id?.let { viewModel.merge(source.id, it) }
                        merging = null
                    },
                    onDismiss = { merging = null },
                )
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New payee") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Payee name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("payee-new-name")) },
            confirmButton = { TextButton(onClick = { viewModel.create(name); adding = false }, enabled = name.isNotBlank(), modifier = Modifier.testTag("payee-new-save")) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { payee ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete payee?") },
            text = { Text("${payee.name} is unused and not referenced by any rule. It will be removed; History can undo this.") },
            confirmButton = { TextButton(onClick = { viewModel.delete(payee.id); confirmDelete = null }, modifier = Modifier.testTag("payee-delete-confirm")) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    showingRulesFor?.let { payee ->
        val forPayee = allRules.filter { payee.id in it.payeeIds }
        AlertDialog(
            onDismissRequest = { showingRulesFor = null },
            title = { Text(if (payee.isTransfer) "Transfer rules" else "Rules for ${payee.name}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("payee-rules-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (forPayee.isEmpty()) {
                        Text("No rules. Create a rule that applies whenever this payee is used.", style = MaterialTheme.typography.bodyMedium)
                    }
                    forPayee.forEach { rule ->
                        Column(Modifier.fillMaxWidth().clickable { showingRulesFor = null; onEditRule(rule.id, payee.id) }.testTag("payee-rule-${rule.id}")) {
                            Text("Stage: ${rule.stage}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            rule.conditions.forEach { Text("If $it", style = MaterialTheme.typography.bodyMedium) }
                            rule.actions.forEach { Text("Then $it", style = MaterialTheme.typography.bodyMedium) }
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showingRulesFor = null; onEditRule(null, payee.id) }, modifier = Modifier.testTag("payee-rule-add")) { Text("Create rule") } },
            dismissButton = { TextButton(onClick = { showingRulesFor = null }) { Text("Close") } },
        )
    }
}
