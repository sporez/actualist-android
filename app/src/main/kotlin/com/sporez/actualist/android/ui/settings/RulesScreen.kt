package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.sporez.actualist.android.ui.Loadable
import com.sporez.actualist.android.ui.components.ErrorBanner
import com.sporez.actualist.android.ui.components.LoadingBox
import com.sporez.actualist.android.ui.components.MessageBox
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.android.ui.reloading
import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.rules.RuleEngine
import com.sporez.actualist.core.rules.RuleValue
import com.sporez.actualist.core.store.BudgetSession
import kotlinx.coroutines.flow.MutableStateFlow

data class RuleSummary(val id: String, val stage: String, val conditions: List<String>, val join: String, val actions: List<String>)

/** The budget's rules, with ids resolved to names. Tap one to edit it; [RuleEditorScreen] edits. */
class RulesViewModel(session: BudgetSession) : ViewModel() {
    val rules = reloading(session, MutableStateFlow(Unit)) {
        val names = HashMap<String, String>()
        ledger.payees().forEach { names[it.id] = it.name }
        ledger.categories(includeHidden = true).forEach { names[it.id] = it.name }
        ledger.accounts().forEach { names[it.id] = it.name }
        RuleEngine(database).fetchRules().map { rule ->
            val draft = rule.executionDraft()
            fun describe(field: String?, value: RuleValue): String = when {
                value is RuleValue.Text && names.containsKey(value.value) -> names.getValue(value.value)
                value is RuleValue.Num && field == "amount" -> Money.format(value.value.toLong())
                value is RuleValue.Arr -> value.values.joinToString(", ") { describe(field, it) }
                value is RuleValue.Text -> "\"${value.value}\""
                value is RuleValue.Num -> value.value.toLong().toString()
                value is RuleValue.Bool -> value.value.toString()
                value is RuleValue.Null -> "nothing"
                else -> "…"
            }
            RuleSummary(
                id = rule.id,
                stage = rule.rawStage ?: "default",
                conditions = draft?.conditions?.map { "${it.editorField} ${it.op} ${describe(it.editorField, it.value)}" } ?: listOf("(unreadable conditions)"),
                join = draft?.join?.raw ?: "and",
                actions = draft?.actions?.map { action ->
                    listOfNotNull(action.op, action.editorField, describe(action.editorField, action.value)).joinToString(" ")
                } ?: listOf("(unreadable actions)"),
            )
        }
    }
}

@Composable
fun RulesScreen(viewModel: RulesViewModel, onEdit: (String?) -> Unit) {
    val state by viewModel.rules.collectAsState()
    when (val current = state) {
        Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBanner(current.message, Modifier.padding(16.dp))
        is Loadable.Ready -> LazyColumn(Modifier.fillMaxSize().testTag("rules-screen"), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                androidx.compose.material3.OutlinedButton(onClick = { onEdit(null) }, modifier = Modifier.fillMaxWidth().testTag("rule-add")) { Text("Add rule") }
            }
            if (current.value.isEmpty()) {
                item { Text("No rules yet. Rules run in the transaction editor, on CSV import and on reconciliation adjustments.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(current.value, key = { it.id }) { rule ->
                SectionCard(Modifier.clickable { onEdit(rule.id) }.testTag("rule-${rule.id}")) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("If (${rule.join})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        rule.conditions.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Text("Then", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        rule.actions.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Text("Stage: ${rule.stage}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
