package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.sporez.actualist.android.ui.components.ErrorBanner
import com.sporez.actualist.android.ui.components.LoadingBox
import com.sporez.actualist.android.ui.components.PickerDialog
import com.sporez.actualist.android.ui.components.PickerOption
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.rules.RuleAction
import com.sporez.actualist.core.rules.RuleCondition
import com.sporez.actualist.core.rules.RuleConditionJoin
import com.sporez.actualist.core.rules.RuleDraft
import com.sporez.actualist.core.rules.RuleEngine
import com.sporez.actualist.core.rules.RuleFields
import com.sporez.actualist.core.rules.RuleStage
import com.sporez.actualist.core.rules.RuleValue
import com.sporez.actualist.core.rules.RuleWrites
import com.sporez.actualist.core.store.BudgetSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One editable row of the form; [value] is an id, text, or an amount typed by the user. */
data class RuleLine(val field: String, val op: String, val value: String)

/** The fields and operators this editor offers (a subset of what the engine evaluates). */
object RuleEditorFields {
    val conditionFields = listOf("payee", "imported_payee", "notes", "account", "category", "amount")
    val actionFields = listOf("category", "payee", "notes", "cleared")

    fun conditionOps(field: String): List<String> = when (RuleFields.valueKind(field)) {
        com.sporez.actualist.core.rules.RuleValueKind.ID -> listOf("is", "isNot")
        com.sporez.actualist.core.rules.RuleValueKind.STRING -> listOf("is", "contains", "doesNotContain", "matches")
        com.sporez.actualist.core.rules.RuleValueKind.NUMBER -> listOf("is", "gt", "gte", "lt", "lte")
        else -> emptyList()
    }

    fun label(field: String) = when (field) {
        "imported_payee" -> "Imported payee"
        else -> field.replaceFirstChar { it.uppercase() }
    }

    fun opLabel(op: String) = when (op) {
        "isNot" -> "is not"
        "doesNotContain" -> "does not contain"
        "matches" -> "matches (regex)"
        "gt" -> "is greater than"
        "gte" -> "is at least"
        "lt" -> "is less than"
        "lte" -> "is at most"
        else -> op
    }
}

data class RuleEditorData(
    val names: Map<String, String>,
    val payees: List<PickerOption>,
    val categories: List<PickerOption>,
    val accounts: List<PickerOption>,
    /** Null for a new rule; a rule this editor cannot represent is shown read-only. */
    val existing: RuleDraft?,
    val editable: Boolean,
)

class RuleEditorViewModel(private val session: BudgetSession, private val ruleId: String?) : ViewModel() {
    private val _data = MutableStateFlow<RuleEditorData?>(null)
    val data: StateFlow<RuleEditorData?> = _data.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _data.value = session.read {
                    val payees = ledger.payees().filter { !it.isTransfer }.map { PickerOption(it.id, it.name) }
                    val categories = ledger.categories(includeHidden = true).map { PickerOption(it.id, it.name, it.groupName) }
                    val accounts = ledger.accounts().map { PickerOption(it.id, it.name) }
                    val existing = ruleId?.let { id -> RuleEngine(database).fetchRules().firstOrNull { it.id == id } }
                    val draft = existing?.draft
                    RuleEditorData(
                        names = (payees + categories + accounts).associate { (it.id ?: "") to it.title },
                        payees = payees,
                        categories = categories,
                        accounts = accounts,
                        existing = draft ?: existing?.executionDraft(),
                        editable = ruleId == null || (draft != null && representable(draft)),
                    )
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "The rule could not be loaded."
            }
        }
    }

    private fun representable(draft: RuleDraft): Boolean =
        draft.stage == RuleStage.NORMAL &&
            draft.conditions.all { it.fieldName in RuleEditorFields.conditionFields && it.op in RuleEditorFields.conditionOps(it.fieldName) && it.options.isNullOrEmpty() } &&
            draft.actions.all { it.op == "set" && it.fieldName in RuleEditorFields.actionFields && it.options.isNullOrEmpty() }

    fun save(join: RuleConditionJoin, conditions: List<RuleLine>, actions: List<RuleLine>) = launch {
        val draft = RuleDraft(
            stage = RuleStage.NORMAL,
            join = join,
            conditions = conditions.map { RuleCondition(it.field, it.op, value(it.field, it.value), RuleFields.valueKind(it.field)?.raw) },
            actions = actions.map { RuleAction("set", it.field, value(it.field, it.value), RuleFields.valueKind(it.field)?.raw) },
        )
        session.write { if (ruleId == null) RuleWrites(database).create(draft) else RuleWrites(database).update(ruleId, draft) }
    }

    fun delete() = launch { session.write { RuleWrites(database).delete(ruleId!!) } }

    private fun value(field: String, text: String): RuleValue = when (RuleFields.valueKind(field)) {
        com.sporez.actualist.core.rules.RuleValueKind.NUMBER -> RuleValue.Num((Money.parse(text) ?: error("Enter an amount like -12.34")).toDouble())
        com.sporez.actualist.core.rules.RuleValueKind.BOOLEAN -> RuleValue.Bool(text == "true")
        else -> RuleValue.Text(text)
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                _done.value = true
            } catch (e: Exception) {
                _error.value = e.message ?: "The rule could not be saved."
            }
        }
    }

    companion object {
        /** The form's text for a stored value: amounts are minor units shown as decimals. */
        fun text(field: String, value: RuleValue): String = when {
            RuleFields.valueKind(field) == com.sporez.actualist.core.rules.RuleValueKind.NUMBER ->
                value.integer?.let { Money.format(it, symbol = "", grouping = false) } ?: ""
            value is RuleValue.Bool -> value.value.toString()
            else -> value.string ?: ""
        }
    }
}

@Composable
fun RuleEditorScreen(viewModel: RuleEditorViewModel, onDone: () -> Unit) {
    val data by viewModel.data.collectAsState()
    val error by viewModel.error.collectAsState()
    val done by viewModel.done.collectAsState()
    LaunchedEffect(done) { if (done) onDone() }
    val loaded = data
    if (loaded == null) {
        if (error != null) ErrorBanner(error!!, Modifier.padding(16.dp)) else LoadingBox()
        return
    }
    val existing = loaded.existing
    var join by remember { mutableStateOf(existing?.join ?: RuleConditionJoin.AND) }
    var conditions by remember {
        mutableStateOf(existing?.conditions?.map { RuleLine(it.fieldName, it.op, RuleEditorViewModel.text(it.fieldName, it.value)) } ?: listOf(RuleLine("payee", "is", "")))
    }
    var actions by remember {
        mutableStateOf(existing?.actions?.map { RuleLine(it.fieldName ?: "", it.op, RuleEditorViewModel.text(it.fieldName ?: "", it.value)) } ?: listOf(RuleLine("category", "set", "")))
    }
    var picking by remember { mutableStateOf<Triple<String, Int, String>?>(null) }
    val valid = conditions.isNotEmpty() && actions.isNotEmpty() &&
        conditions.all { it.value.isNotBlank() && (RuleFields.valueKind(it.field) != com.sporez.actualist.core.rules.RuleValueKind.NUMBER || Money.parse(it.value) != null) } &&
        actions.all { it.value.isNotBlank() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("rule-editor"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        error?.let { ErrorBanner(it, Modifier.testTag("rule-error")) }
        if (!loaded.editable) {
            Text("This rule uses options this editor does not offer yet. You can delete it here or edit it in Actual.", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("If", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { join = if (join == RuleConditionJoin.AND) RuleConditionJoin.OR else RuleConditionJoin.AND }, enabled = loaded.editable, modifier = Modifier.testTag("rule-join")) {
                Text(if (join == RuleConditionJoin.AND) "all of these match" else "any of these match")
            }
        }
        conditions.forEachIndexed { index, line ->
            LineCard(
                line = line,
                kind = "condition",
                index = index,
                enabled = loaded.editable,
                names = loaded.names,
                onChange = { updated -> conditions = conditions.toMutableList().also { it[index] = updated } },
                onPick = { what -> picking = Triple("condition", index, what) },
                onRemove = if (conditions.size > 1) ({ conditions = conditions.filterIndexed { i, _ -> i != index } }) else null,
            )
        }
        if (loaded.editable) TextButton(onClick = { conditions = conditions + RuleLine("payee", "is", "") }, modifier = Modifier.testTag("rule-add-condition")) { Text("Add condition") }
        Text("Then", style = MaterialTheme.typography.titleMedium)
        actions.forEachIndexed { index, line ->
            LineCard(
                line = line,
                kind = "action",
                index = index,
                enabled = loaded.editable,
                names = loaded.names,
                onChange = { updated -> actions = actions.toMutableList().also { it[index] = updated } },
                onPick = { what -> picking = Triple("action", index, what) },
                onRemove = if (actions.size > 1) ({ actions = actions.filterIndexed { i, _ -> i != index } }) else null,
            )
        }
        if (loaded.editable) TextButton(onClick = { actions = actions + RuleLine("notes", "set", "") }, modifier = Modifier.testTag("rule-add-action")) { Text("Add action") }
        Row {
            if (loaded.editable) {
                Button(onClick = { viewModel.save(join, conditions, actions) }, enabled = valid, modifier = Modifier.testTag("rule-save")) { Text("Save rule") }
            }
            if (existing != null) {
                TextButton(onClick = { viewModel.delete() }, modifier = Modifier.testTag("rule-delete")) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
            TextButton(onClick = onDone) { Text("Cancel") }
        }
    }

    picking?.let { (kind, index, what) ->
        val lines = if (kind == "condition") conditions else actions
        fun set(line: RuleLine) {
            if (kind == "condition") conditions = conditions.toMutableList().also { it[index] = line } else actions = actions.toMutableList().also { it[index] = line }
            picking = null
        }
        val line = lines[index]
        when (what) {
            "field" -> PickerDialog(
                title = "Field",
                options = (if (kind == "condition") RuleEditorFields.conditionFields else RuleEditorFields.actionFields).map { PickerOption(it, RuleEditorFields.label(it)) },
                onPick = { option ->
                    val field = option.id!!
                    set(RuleLine(field, if (kind == "condition") RuleEditorFields.conditionOps(field).first() else "set", if (field == "cleared") "true" else ""))
                },
                onDismiss = { picking = null },
            )
            "op" -> PickerDialog(
                title = "Operator",
                options = RuleEditorFields.conditionOps(line.field).map { PickerOption(it, RuleEditorFields.opLabel(it)) },
                onPick = { option -> set(line.copy(op = option.id!!)) },
                onDismiss = { picking = null },
            )
            "value" -> PickerDialog(
                title = RuleEditorFields.label(line.field),
                options = when (line.field) {
                    "payee" -> loaded.payees
                    "category" -> loaded.categories
                    "account" -> loaded.accounts
                    "cleared" -> listOf(PickerOption("true", "Cleared"), PickerOption("false", "Not cleared"))
                    else -> emptyList()
                },
                searchable = true,
                onPick = { option -> set(line.copy(value = option.id ?: "")) },
                onDismiss = { picking = null },
            )
        }
    }
}

@Composable
private fun LineCard(
    line: RuleLine,
    kind: String,
    index: Int,
    enabled: Boolean,
    names: Map<String, String>,
    onChange: (RuleLine) -> Unit,
    onPick: (String) -> Unit,
    onRemove: (() -> Unit)?,
) {
    val valueKind = RuleFields.valueKind(line.field)
    val picked = valueKind == com.sporez.actualist.core.rules.RuleValueKind.ID || valueKind == com.sporez.actualist.core.rules.RuleValueKind.BOOLEAN
    SectionCard(Modifier.testTag("rule-$kind-$index")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onPick("field") }, enabled = enabled, modifier = Modifier.testTag("rule-$kind-$index-field")) { Text(RuleEditorFields.label(line.field)) }
                if (kind == "condition") {
                    TextButton(onClick = { onPick("op") }, enabled = enabled, modifier = Modifier.testTag("rule-$kind-$index-op")) { Text(RuleEditorFields.opLabel(line.op)) }
                } else {
                    Text("  set to", modifier = Modifier.padding(horizontal = 8.dp))
                }
                if (onRemove != null && enabled) TextButton(onClick = onRemove) { Text("Remove") }
            }
            if (picked) {
                OutlinedButton(onClick = { onPick("value") }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("rule-$kind-$index-value")) {
                    Text(when {
                        line.value.isBlank() -> "Choose…"
                        line.field == "cleared" -> if (line.value == "true") "Cleared" else "Not cleared"
                        else -> names[line.value] ?: line.value
                    })
                }
            } else {
                OutlinedTextField(
                    value = line.value,
                    onValueChange = { onChange(line.copy(value = it)) },
                    enabled = enabled,
                    singleLine = true,
                    label = { Text(if (valueKind == com.sporez.actualist.core.rules.RuleValueKind.NUMBER) "Amount (negative for outflow)" else "Text") },
                    modifier = Modifier.fillMaxWidth().testTag("rule-$kind-$index-text"),
                )
            }
        }
    }
}
