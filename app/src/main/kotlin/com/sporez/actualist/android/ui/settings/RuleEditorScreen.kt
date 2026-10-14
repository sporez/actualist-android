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
        com.sporez.actualist.core.rules.RuleValueKind.ID -> listOf("is", "isNot", "oneOf", "notOneOf")
        com.sporez.actualist.core.rules.RuleValueKind.STRING -> listOf("is", "isNot", "contains", "doesNotContain", "matches")
        com.sporez.actualist.core.rules.RuleValueKind.NUMBER -> listOf("is", "isapprox", "isbetween", "gt", "gte", "lt", "lte")
        else -> emptyList()
    }

    /** Notes actions also add text before or after the existing notes; Actual stores these without a field. */
    val notesActionOps = listOf("set", "prepend-notes", "append-notes")

    fun actionOpLabel(op: String) = when (op) {
        "prepend-notes" -> "add before"
        "append-notes" -> "add after"
        else -> "set to"
    }

    fun stageLabel(stage: RuleStage) = when (stage) {
        RuleStage.PRE -> "Pre"
        RuleStage.NORMAL -> "Default"
        RuleStage.POST -> "Post"
    }

    fun label(field: String) = when (field) {
        "imported_payee" -> "Imported payee"
        else -> field.replaceFirstChar { it.uppercase() }
    }

    /** Operators whose value is a list of ids (stored as a JSON array, edited as a set of picks). */
    fun isMulti(op: String) = op == "oneOf" || op == "notOneOf"

    /** "is between" keeps both amounts in the line's text as "low|high". */
    fun rangeParts(text: String): Pair<String, String> = text.substringBefore('|') to text.substringAfter('|', "")

    /** Whether a condition's typed value is complete for its field and operator. */
    fun conditionValid(line: RuleLine): Boolean = when {
        line.value.isBlank() -> false
        RuleFields.valueKind(line.field) != com.sporez.actualist.core.rules.RuleValueKind.NUMBER -> true
        line.op == "isbetween" -> rangeParts(line.value).let { (low, high) -> Money.parse(low) != null && Money.parse(high) != null }
        else -> Money.parse(line.value) != null
    }

    fun opLabel(op: String) = when (op) {
        "isNot" -> "is not"
        "oneOf" -> "is one of"
        "notOneOf" -> "is not one of"
        "isapprox" -> "is approximately"
        "isbetween" -> "is between"
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
        draft.conditions.all { it.fieldName in RuleEditorFields.conditionFields && it.op in RuleEditorFields.conditionOps(it.fieldName) && it.options.isNullOrEmpty() } &&
            draft.actions.all { action ->
                action.options.isNullOrEmpty() && when (action.op) {
                    "set" -> action.fieldName in RuleEditorFields.actionFields
                    "prepend-notes", "append-notes" -> action.fieldName == null && action.value.isStringLike
                    else -> false
                }
            }

    fun save(stage: RuleStage, join: RuleConditionJoin, conditions: List<RuleLine>, actions: List<RuleLine>) = launch {
        val draft = RuleDraft(
            stage = stage,
            join = join,
            conditions = conditions.map { RuleCondition(it.field, it.op, value(it.field, it.value, it.op), RuleFields.valueKind(it.field)?.raw) },
            actions = actions.map {
                if (it.op == "set") RuleAction("set", it.field, value(it.field, it.value), RuleFields.valueKind(it.field)?.raw)
                else RuleAction(it.op, null, RuleValue.Text(it.value))
            },
        )
        session.write { if (ruleId == null) RuleWrites(database).create(draft) else RuleWrites(database).update(ruleId, draft) }
    }

    fun delete() = launch { session.write { RuleWrites(database).delete(ruleId!!) } }

    private fun value(field: String, text: String, op: String = "set"): RuleValue = if (RuleEditorFields.isMulti(op)) {
        RuleValue.Arr(text.split(',').filter { it.isNotBlank() }.map { RuleValue.Text(it) })
    } else if (op == "isbetween") {
        val (low, high) = RuleEditorFields.rangeParts(text)
        fun amount(part: String) = RuleValue.Num((Money.parse(part) ?: error("Enter amounts like -12.34")).toDouble())
        RuleValue.Obj(mapOf("num1" to amount(low), "num2" to amount(high)))
    } else when (RuleFields.valueKind(field)) {
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
            value is RuleValue.Arr -> value.values.mapNotNull { it.string }.joinToString(",")
            value is RuleValue.Obj -> listOf("num1", "num2").joinToString("|") { key -> value.values[key]?.integer?.let { Money.format(it, symbol = "", grouping = false) } ?: "" }
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
        mutableStateOf(existing?.actions?.map { RuleLine(it.fieldName ?: "notes", it.op, RuleEditorViewModel.text(it.fieldName ?: "notes", it.value)) } ?: listOf(RuleLine("category", "set", "")))
    }
    var stage by remember { mutableStateOf(existing?.stage ?: RuleStage.NORMAL) }
    var picking by remember { mutableStateOf<Triple<String, Int, String>?>(null) }
    val valid = conditions.isNotEmpty() && actions.isNotEmpty() &&
        conditions.all { RuleEditorFields.conditionValid(it) } &&
        actions.all { it.value.isNotBlank() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("rule-editor"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        error?.let { ErrorBanner(it, Modifier.testTag("rule-error")) }
        if (!loaded.editable) {
            Text("This rule uses options this editor does not offer yet. You can delete it here or edit it in Actual.", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Stage", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
            for (option in RuleStage.values()) {
                androidx.compose.material3.FilterChip(
                    selected = stage == option,
                    onClick = { stage = option },
                    enabled = loaded.editable,
                    label = { Text(RuleEditorFields.stageLabel(option)) },
                    modifier = Modifier.padding(end = 4.dp).testTag("rule-stage-${option.raw}"),
                )
            }
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
                Button(onClick = { viewModel.save(stage, join, conditions, actions) }, enabled = valid, modifier = Modifier.testTag("rule-save")) { Text("Save rule") }
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
            "op" -> if (kind == "action") PickerDialog(
                title = "Notes",
                options = RuleEditorFields.notesActionOps.map { PickerOption(it, RuleEditorFields.actionOpLabel(it)) },
                onPick = { option -> set(line.copy(op = option.id!!)) },
                onDismiss = { picking = null },
            ) else PickerDialog(
                title = "Operator",
                options = RuleEditorFields.conditionOps(line.field).map { PickerOption(it, RuleEditorFields.opLabel(it)) },
                onPick = { option ->
                    val op = option.id!!
                    // Switching between a single value and a list or range clears the value.
                    val reshaped = RuleEditorFields.isMulti(op) != RuleEditorFields.isMulti(line.op) || (op == "isbetween") != (line.op == "isbetween")
                    set(line.copy(op = op, value = if (reshaped) "" else line.value))
                },
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
                onPick = { option ->
                    val id = option.id ?: ""
                    if (RuleEditorFields.isMulti(line.op)) {
                        // Each pick adds or removes one id from the list.
                        val ids = line.value.split(',').filter { it.isNotBlank() }
                        set(line.copy(value = (if (id in ids) ids - id else ids + id).joinToString(",")))
                    } else {
                        set(line.copy(value = id))
                    }
                },
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
                } else if (line.field == "notes") {
                    TextButton(onClick = { onPick("op") }, enabled = enabled, modifier = Modifier.testTag("rule-$kind-$index-op")) { Text(RuleEditorFields.actionOpLabel(line.op)) }
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
                        RuleEditorFields.isMulti(line.op) -> line.value.split(',').filter { it.isNotBlank() }.joinToString(", ") { names[it] ?: it } + "  (tap to add or remove)"
                        else -> names[line.value] ?: line.value
                    })
                }
            } else if (line.op == "isbetween") {
                val (low, high) = RuleEditorFields.rangeParts(line.value)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(low, { onChange(line.copy(value = "$it|$high")) }, enabled = enabled, singleLine = true, label = { Text("From") }, modifier = Modifier.weight(1f).testTag("rule-$kind-$index-text"))
                    OutlinedTextField(high, { onChange(line.copy(value = "$low|$it")) }, enabled = enabled, singleLine = true, label = { Text("To") }, modifier = Modifier.weight(1f).testTag("rule-$kind-$index-text2"))
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
