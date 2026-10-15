@file:OptIn(ExperimentalMaterial3Api::class)

package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import com.sporez.actualist.android.ui.components.MessageBox
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.android.ui.reloading
import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.rules.RuleEngine
import com.sporez.actualist.core.rules.RulePayees
import com.sporez.actualist.core.rules.RuleValue
import com.sporez.actualist.core.rules.RuleWrites
import com.sporez.actualist.core.store.BudgetSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class RuleSummary(
    val id: String,
    val stage: String,
    val conditions: List<String>,
    val join: String,
    val actions: List<String>,
    /** Payee ids the rule's conditions and actions refer to. */
    val payeeIds: Set<String> = emptySet(),
) {
    /** Everything a search matches: conditions, actions and stage. */
    val searchText: String get() = (conditions + actions + stage).joinToString(" ")
}

/** The live rules of the budget with ids resolved to names; completed schedule rules are left out. */
fun BudgetSession.ruleSummaries(): List<RuleSummary> {
    val names = HashMap<String, String>()
    ledger.payees().forEach { names[it.id] = it.name }
    ledger.categories(includeHidden = true).forEach { names[it.id] = it.name }
    ledger.accounts().forEach { names[it.id] = it.name }
    return RuleEngine(database).fetchRules().filter { !it.isCompletedScheduleRule }.map { rule ->
        val draft = rule.executionDraft()
        fun describe(field: String?, value: RuleValue): String = when {
            value is RuleValue.Text && names.containsKey(value.value) -> names.getValue(value.value)
            value is RuleValue.Num && field == "amount" -> Money.format(value.value.toLong())
            value is RuleValue.Arr -> value.values.joinToString(", ") { describe(field, it) }
            value is RuleValue.Obj && value.values.containsKey("num1") -> "${describe(field, value.values.getValue("num1"))} and ${value.values["num2"]?.let { describe(field, it) } ?: "…"}"
            value is RuleValue.Text -> "\"${value.value}\""
            value is RuleValue.Num -> value.value.toLong().toString()
            value is RuleValue.Bool -> value.value.toString()
            value is RuleValue.Null -> "nothing"
            else -> "…"
        }
        RuleSummary(
            id = rule.id,
            stage = rule.rawStage ?: "default",
            conditions = draft?.conditions?.map { condition ->
                // On/off-budget conditions carry no value.
                if (condition.value is RuleValue.Null && (condition.op == "onBudget" || condition.op == "offBudget")) "${condition.editorField} ${condition.op}"
                else "${condition.editorField} ${condition.op} ${describe(condition.editorField, condition.value)}"
            } ?: listOf("(unreadable conditions)"),
            join = draft?.join?.raw ?: "and",
            actions = draft?.actions?.map { action ->
                if (action.op == "delete-transaction") "delete-transaction"
                else listOfNotNull(action.op, action.editorField, describe(action.editorField, action.value)).joinToString(" ")
            } ?: listOf("(unreadable actions)"),
            payeeIds = RulePayees.referencedIds(rule).orEmpty(),
        )
    }
}

/** The budget's rules, with ids resolved to names. Tap one to edit it; [RuleEditorScreen] edits. */
class RulesViewModel(private val session: BudgetSession) : ViewModel() {
    val rules = reloading(session, MutableStateFlow(Unit)) { ruleSummaries() }
    val messages = MessageState()
    val query = MutableStateFlow("")

    /** Deletes a rule (the screen asks first); the session write makes it undoable. */
    fun delete(id: String) {
        viewModelScope.launch {
            try {
                session.write { RuleWrites(database).delete(id) }
                messages.show("Rule deleted")
            } catch (error: Exception) {
                messages.show(error.message ?: "The rule could not be deleted.")
            }
        }
    }

    /** Saves a copy of a rule (Actualist's duplicate); the session write makes it undoable. */
    fun duplicate(id: String) {
        viewModelScope.launch {
            try {
                session.write {
                    val rule = RuleEngine(database).fetchRules().firstOrNull { it.id == id }
                    val draft = rule?.draft ?: error("This rule cannot be duplicated here.")
                    RuleWrites(database).create(draft)
                }
                messages.show("Rule duplicated")
            } catch (error: Exception) {
                messages.show(error.message ?: "The rule could not be duplicated.")
            }
        }
    }
}

private fun stageHeading(stage: String) = when (stage) {
    "pre" -> "Pre-stage rules"
    "post" -> "Post-stage rules"
    else -> "Default-stage rules"
}

@Composable
fun RulesScreen(viewModel: RulesViewModel, onEdit: (String?) -> Unit) {
    val state by viewModel.rules.collectAsState()
    val query by viewModel.query.collectAsState()
    val message by viewModel.messages.message.collectAsState()
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete Rule?") },
            text = { Text("Future transactions will no longer be processed by this rule.") },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; viewModel.delete(id) }, modifier = Modifier.testTag("rules-delete-confirm")) {
                    Text("Delete Rule", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }, modifier = Modifier.testTag("rules-delete-cancel")) { Text("Cancel") } },
        )
    }
    when (val current = state) {
        Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBanner(current.message, Modifier.padding(16.dp))
        is Loadable.Ready -> LazyColumn(Modifier.fillMaxSize().testTag("rules-screen"), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                androidx.compose.material3.OutlinedButton(onClick = { onEdit(null) }, modifier = Modifier.fillMaxWidth().testTag("rule-add")) { Text("Add rule") }
            }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("rules-message")) } }
            item {
                OutlinedTextField(query, { viewModel.query.value = it }, label = { Text("Search rules") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("rules-search"))
            }
            val shown = current.value.filter { query.isBlank() || it.searchText.contains(query.trim(), ignoreCase = true) }
            if (current.value.isEmpty()) {
                item { Text("No rules yet. Rules run in the transaction editor, on CSV import and on reconciliation adjustments.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (current.value.isNotEmpty() && shown.isEmpty()) {
                item { Text("No matching rules.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            for (stage in shown.map { it.stage }.distinct()) {
                item(key = "stage-header-$stage") {
                    Text(stageHeading(stage), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("rules-stage-$stage"))
                }
                items(shown.filter { it.stage == stage }, key = { it.id }) { rule ->
                    // Swipe left to delete (after a confirmation) or right to duplicate; the card snaps back either way.
                    val swipe = rememberSwipeToDismissBoxState(
                        confirmValueChange = { target ->
                            when (target) {
                                SwipeToDismissBoxValue.EndToStart -> pendingDelete = rule.id
                                SwipeToDismissBoxValue.StartToEnd -> viewModel.duplicate(rule.id)
                                SwipeToDismissBoxValue.Settled -> Unit
                            }
                            false
                        },
                    )
                    SwipeToDismissBox(
                        state = swipe,
                        backgroundContent = {
                            val deleting = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
                            Box(
                                Modifier.fillMaxSize().background(if (deleting) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 20.dp),
                                contentAlignment = if (deleting) Alignment.CenterEnd else Alignment.CenterStart,
                            ) {
                                Text(if (deleting) "Delete" else "Duplicate", style = MaterialTheme.typography.labelLarge)
                            }
                        },
                        modifier = Modifier.testTag("rule-swipe-${rule.id}"),
                    ) {
                        SectionCard(Modifier.clickable { onEdit(rule.id) }.testTag("rule-${rule.id}")) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("If (${rule.join})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                rule.conditions.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                Text("Then", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                rule.actions.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Stage: ${rule.stage}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { viewModel.duplicate(rule.id) }, modifier = Modifier.testTag("rule-duplicate-${rule.id}")) { Text("Duplicate") }
                                    TextButton(onClick = { pendingDelete = rule.id }, modifier = Modifier.testTag("rule-delete-${rule.id}")) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
