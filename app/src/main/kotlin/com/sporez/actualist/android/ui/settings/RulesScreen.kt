package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
                listOfNotNull(action.op, action.editorField, describe(action.editorField, action.value)).joinToString(" ")
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

@Composable
fun RulesScreen(viewModel: RulesViewModel, onEdit: (String?) -> Unit) {
    val state by viewModel.rules.collectAsState()
    val query by viewModel.query.collectAsState()
    val message by viewModel.messages.message.collectAsState()
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
            items(shown, key = { it.id }) { rule ->
                SectionCard(Modifier.clickable { onEdit(rule.id) }.testTag("rule-${rule.id}")) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("If (${rule.join})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        rule.conditions.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Text("Then", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        rule.actions.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Stage: ${rule.stage}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.duplicate(rule.id) }, modifier = Modifier.testTag("rule-duplicate-${rule.id}")) { Text("Duplicate") }
                        }
                    }
                }
            }
        }
    }
}
