package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.sporez.actualist.android.ui.components.money
import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.rules.RuleAction
import com.sporez.actualist.core.rules.RuleCondition
import com.sporez.actualist.core.rules.RuleConditionJoin
import com.sporez.actualist.core.rules.RuleDraft
import com.sporez.actualist.core.rules.RuleEngine
import com.sporez.actualist.core.rules.RuleFields
import com.sporez.actualist.core.rules.RuleMatchPreview
import com.sporez.actualist.core.rules.RuleMatches
import com.sporez.actualist.core.rules.RuleStage
import com.sporez.actualist.core.rules.RuleValue
import com.sporez.actualist.core.rules.RuleWrites
import com.sporez.actualist.core.store.BudgetSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One editable row of the form; [value] is an id, text, or an amount typed by the user. */
data class RuleLine(val field: String, val op: String, val value: String)

/** The fields and operators this editor offers (a subset of what the engine evaluates). */
object RuleEditorFields {
    val conditionFields = listOf(
        "payee", "payee_name", "imported_payee", "notes", "account", "category", "category_group",
        "amount", "amount-inflow", "amount-outflow", "date", "cleared", "reconciled",
    )
    val actionFields = listOf("category", "payee", "notes", "cleared", "account", "amount", "date")

    /** The "field" of a "Delete transaction" action line: that action sets nothing, so it has no real field. */
    const val DELETE_FIELD = "transaction"
    const val DELETE_OP = "delete-transaction"

    /** The fields the action picker offers, ending with the delete action. */
    val actionPickerFields = actionFields + DELETE_FIELD

    fun conditionOps(field: String): List<String> = when (RuleFields.valueKind(field)) {
        com.sporez.actualist.core.rules.RuleValueKind.ID -> listOf("is", "isNot", "oneOf", "notOneOf") + if (field == "account") listOf("onBudget", "offBudget") else emptyList()
        // Actual allows "one of" on imported payee and payee name, not on notes.
        com.sporez.actualist.core.rules.RuleValueKind.STRING ->
            listOf("is", "isNot") + (if (field == "notes") emptyList() else listOf("oneOf", "notOneOf")) +
                listOf("contains", "doesNotContain", "matches") + if (field == "notes") listOf("hasTags", "hasAnyTag") else emptyList()
        com.sporez.actualist.core.rules.RuleValueKind.BOOLEAN -> listOf("is")
        com.sporez.actualist.core.rules.RuleValueKind.NUMBER ->
            if (RuleFields.isDirectionalAmountField(field)) listOf("is", "isapprox", "gt", "gte", "lt", "lte") else listOf("is", "isapprox", "isbetween", "gt", "gte", "lt", "lte")
        // Plain dates only; Actual's recurring date values stay view-only.
        com.sporez.actualist.core.rules.RuleValueKind.DATE -> listOf("is", "isapprox", "gt", "gte", "lt", "lte")
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
        "amount-inflow" -> "Amount (inflow)"
        "amount-outflow" -> "Amount (outflow)"
        "payee_name" -> "Payee name"
        "category_group" -> "Category group"
        DELETE_FIELD -> "Delete transaction"
        else -> field.replaceFirstChar { it.uppercase() }
    }

    /** Operators whose value is a list of ids (stored as a JSON array, edited as a set of picks). */
    fun isMulti(op: String) = op == "oneOf" || op == "notOneOf"

    /** Ids are joined with commas in a line's text; free text values one per line, as they may contain commas. */
    fun multiSeparator(field: String): String = if (RuleFields.valueKind(field) == com.sporez.actualist.core.rules.RuleValueKind.STRING) "\n" else ","

    fun multiValues(field: String, text: String): List<String> {
        val parts = text.split(multiSeparator(field))
        return (if (multiSeparator(field) == "\n") parts.map { it.trim() } else parts).filter { it.isNotBlank() }
    }

    fun isBoolean(field: String) = RuleFields.valueKind(field) == com.sporez.actualist.core.rules.RuleValueKind.BOOLEAN

    /** The value a new line of [field] starts with. */
    fun defaultValue(field: String) = if (isBoolean(field)) "true" else ""

    fun booleanLabel(field: String, value: String): String = when {
        field == "reconciled" -> if (value == "true") "Reconciled" else "Not reconciled"
        else -> if (value == "true") "Cleared" else "Not cleared"
    }

    fun booleanOptions(field: String) = listOf("true", "false").map { PickerOption(it, booleanLabel(field, it)) }

    /** Whether an action line is complete: a delete action needs nothing, an amount or date must parse. */
    fun actionValid(line: RuleLine): Boolean = when {
        line.op == DELETE_OP -> true
        line.op != "set" -> line.value.isNotBlank()
        RuleFields.valueKind(line.field) == com.sporez.actualist.core.rules.RuleValueKind.NUMBER -> Money.parse(line.value) != null
        RuleFields.valueKind(line.field) == com.sporez.actualist.core.rules.RuleValueKind.DATE ->
            runCatching { java.time.LocalDate.parse(line.value.trim()) }.isSuccess
        else -> line.value.isNotBlank()
    }

    /** Operators that take no value (Actual stores null). */
    fun isValueless(op: String) = op == "onBudget" || op == "offBudget"

    /** "is between" keeps both amounts in the line's text as "low|high". */
    fun rangeParts(text: String): Pair<String, String> = text.substringBefore('|') to text.substringAfter('|', "")

    /** Whether a condition's typed value is complete for its field and operator. */
    fun conditionValid(line: RuleLine): Boolean = when {
        isValueless(line.op) -> true
        line.value.isBlank() -> false
        isMulti(line.op) -> multiValues(line.field, line.value).isNotEmpty()
        isBoolean(line.field) -> line.value == "true" || line.value == "false"
        RuleFields.valueKind(line.field) == com.sporez.actualist.core.rules.RuleValueKind.DATE ->
            com.sporez.actualist.core.rules.RuleCondition(line.field, line.op, RuleValue.Text(line.value.trim()), "date").canRoundTripAndEvaluate
        RuleFields.valueKind(line.field) != com.sporez.actualist.core.rules.RuleValueKind.NUMBER -> true
        line.op == "isbetween" -> rangeParts(line.value).let { (low, high) -> Money.parse(low) != null && Money.parse(high) != null }
        else -> Money.parse(line.value) != null
    }

    fun opLabel(op: String, field: String = "") = if (field == "date") when (op) {
        "isapprox" -> "is around"
        "gt" -> "is after"
        "gte" -> "is on or after"
        "lt" -> "is before"
        "lte" -> "is on or before"
        else -> op
    } else when (op) {
        "isNot" -> "is not"
        "oneOf" -> "is one of"
        "notOneOf" -> "is not one of"
        "isapprox" -> "is approximately"
        "isbetween" -> "is between"
        "onBudget" -> "is on budget"
        "offBudget" -> "is off budget"
        "hasTags" -> "has all tags"
        "hasAnyTag" -> "has any tag"
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
    val categoryGroups: List<PickerOption> = emptyList(),
    /** Null for a new rule; a rule this editor cannot represent is shown read-only. */
    val existing: RuleDraft?,
    val editable: Boolean,
)

/** What the editor's match preview shows: the draft's matches among existing transactions, or why not. */
sealed class RuleMatchState {
    data object Idle : RuleMatchState()
    data class Ready(val preview: RuleMatchPreview) : RuleMatchState()
    data class Failed(val message: String) : RuleMatchState()
}

/** [initialPayeeId] seeds a new rule with a "payee is X" condition (the payees page's Add rule). */
class RuleEditorViewModel(private val session: BudgetSession, private val ruleId: String?, val initialPayeeId: String? = null) : ViewModel() {
    private val _matches = MutableStateFlow<RuleMatchState>(RuleMatchState.Idle)
    val matches: StateFlow<RuleMatchState> = _matches.asStateFlow()

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
                    val groups = RuleEngine(database).metadata().categoryGroupNames.map { (id, name) -> PickerOption(id, name) }.sortedBy { it.title.lowercase() }
                    RuleEditorData(
                        names = (payees + categories + accounts + groups).associate { (it.id ?: "") to it.title },
                        payees = payees,
                        categories = categories,
                        accounts = accounts,
                        categoryGroups = groups,
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
        draft.conditions.all {
            it.editorField in RuleEditorFields.conditionFields && it.op in RuleEditorFields.conditionOps(it.editorField) &&
                (it.options.isNullOrEmpty() || RuleFields.isDirectionalAmountField(it.editorField))
        } &&
            draft.actions.all { action ->
                action.options.isNullOrEmpty() && when (action.op) {
                    "set" -> action.editorField in RuleEditorFields.actionFields && action.canRoundTripAndEvaluate
                    "prepend-notes", "append-notes" -> action.fieldName == null && action.value.isStringLike
                    RuleEditorFields.DELETE_OP -> action.canRoundTripAndEvaluate
                    else -> false
                }
            }

    /** Which existing transactions the conditions match (the preview ignores the actions). Incomplete conditions clear it. */
    suspend fun refreshMatches(stage: RuleStage, join: RuleConditionJoin, conditions: List<RuleLine>) {
        if (conditions.isEmpty() || !conditions.all { RuleEditorFields.conditionValid(it) }) {
            _matches.value = RuleMatchState.Idle
            return
        }
        _matches.value = try {
            val draft = buildDraft(stage, join, conditions, emptyList())
            RuleMatchState.Ready(session.read { RuleMatches(database).matching(draft, limit = 25) })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            RuleMatchState.Failed(e.message ?: "The matches could not be read.")
        }
    }

    private fun buildDraft(stage: RuleStage, join: RuleConditionJoin, conditions: List<RuleLine>, actions: List<RuleLine>): RuleDraft =
        RuleDraft(
            stage = stage,
            join = join,
            conditions = conditions.map {
                // Inflow and outflow are amount conditions with a direction option, as Actual stores them.
                val direction = when (it.field) {
                    "amount-inflow" -> mapOf("inflow" to RuleValue.Bool(true))
                    "amount-outflow" -> mapOf("outflow" to RuleValue.Bool(true))
                    else -> null
                }
                RuleCondition(if (direction != null) "amount" else it.field, it.op, value(it.field, it.value, it.op), RuleFields.valueKind(it.field)?.raw, direction)
            },
            actions = actions.map {
                when (it.op) {
                    "set" -> RuleAction("set", it.field, value(it.field, it.value), RuleFields.valueKind(it.field)?.raw)
                    RuleEditorFields.DELETE_OP -> RuleAction(RuleEditorFields.DELETE_OP)
                    else -> RuleAction(it.op, null, RuleValue.Text(it.value))
                }
            },
        )

    fun save(stage: RuleStage, join: RuleConditionJoin, conditions: List<RuleLine>, actions: List<RuleLine>) = launch {
        val draft = buildDraft(stage, join, conditions, actions)
        session.write { if (ruleId == null) RuleWrites(database).create(draft) else RuleWrites(database).update(ruleId, draft) }
    }

    fun delete() = launch { session.write { RuleWrites(database).delete(ruleId!!) } }

    private fun value(field: String, text: String, op: String = "set"): RuleValue = if (RuleEditorFields.isMulti(op)) {
        RuleValue.Arr(RuleEditorFields.multiValues(field, text).map { RuleValue.Text(it) })
    } else if (RuleEditorFields.isValueless(op)) {
        RuleValue.Null
    } else if (op == "isbetween") {
        val (low, high) = RuleEditorFields.rangeParts(text)
        fun amount(part: String) = RuleValue.Num((Money.parse(part) ?: error("Enter amounts like -12.34")).toDouble())
        RuleValue.Obj(mapOf("num1" to amount(low), "num2" to amount(high)))
    } else when (RuleFields.valueKind(field)) {
        com.sporez.actualist.core.rules.RuleValueKind.NUMBER -> RuleValue.Num((Money.parse(text) ?: error("Enter an amount like -12.34")).toDouble())
        com.sporez.actualist.core.rules.RuleValueKind.BOOLEAN -> RuleValue.Bool(text == "true")
        com.sporez.actualist.core.rules.RuleValueKind.DATE -> RuleValue.Text(text.trim())
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
            value is RuleValue.Arr -> value.values.mapNotNull { it.string }.joinToString(RuleEditorFields.multiSeparator(field))
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
        mutableStateOf(existing?.conditions?.map { RuleLine(it.editorField, it.op, RuleEditorViewModel.text(it.editorField, it.value)) } ?: listOf(RuleLine("payee", "is", viewModel.initialPayeeId ?: "")))
    }
    var actions by remember {
        mutableStateOf(
            existing?.actions?.map {
                if (it.op == RuleEditorFields.DELETE_OP) RuleLine(RuleEditorFields.DELETE_FIELD, it.op, "")
                else RuleLine(it.editorField ?: "notes", it.op, RuleEditorViewModel.text(it.editorField ?: "notes", it.value))
            } ?: listOf(RuleLine("category", "set", "")),
        )
    }
    var stage by remember { mutableStateOf(existing?.stage ?: RuleStage.NORMAL) }
    var picking by remember { mutableStateOf<Triple<String, Int, String>?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val valid = conditions.isNotEmpty() && actions.isNotEmpty() &&
        conditions.all { RuleEditorFields.conditionValid(it) } &&
        actions.all { RuleEditorFields.actionValid(it) }

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
        val matchState by viewModel.matches.collectAsState()
        LaunchedEffect(conditions, join, stage) {
            delay(250)
            viewModel.refreshMatches(stage, join, conditions)
        }
        MatchPreviewSection(matchState)
        Row {
            if (loaded.editable) {
                Button(onClick = { viewModel.save(stage, join, conditions, actions) }, enabled = valid, modifier = Modifier.testTag("rule-save")) { Text("Save rule") }
            }
            if (existing != null) {
                TextButton(onClick = { confirmingDelete = true }, modifier = Modifier.testTag("rule-delete")) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
            TextButton(onClick = onDone) { Text("Cancel") }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete Rule?") },
            text = { Text("Future transactions will no longer be processed by this rule.") },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; viewModel.delete() }, modifier = Modifier.testTag("rule-delete-confirm")) {
                    Text("Delete Rule", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }, modifier = Modifier.testTag("rule-delete-cancel")) { Text("Cancel") } },
        )
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
                options = (if (kind == "condition") RuleEditorFields.conditionFields else RuleEditorFields.actionPickerFields).map { PickerOption(it, RuleEditorFields.label(it)) },
                onPick = { option ->
                    val field = option.id!!
                    when {
                        kind == "condition" -> set(RuleLine(field, RuleEditorFields.conditionOps(field).first(), RuleEditorFields.defaultValue(field)))
                        field == RuleEditorFields.DELETE_FIELD -> set(RuleLine(field, RuleEditorFields.DELETE_OP, ""))
                        else -> set(RuleLine(field, "set", RuleEditorFields.defaultValue(field)))
                    }
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
                options = RuleEditorFields.conditionOps(line.field).map { PickerOption(it, RuleEditorFields.opLabel(it, line.field)) },
                onPick = { option ->
                    val op = option.id!!
                    // Switching between a single value and a list or range clears the value.
                    val reshaped = RuleEditorFields.isMulti(op) != RuleEditorFields.isMulti(line.op) || (op == "isbetween") != (line.op == "isbetween") ||
                        RuleEditorFields.isValueless(op) != RuleEditorFields.isValueless(line.op) ||
                        // A year or month only works with "is"; other date operators need a full day.
                        (RuleFields.valueKind(line.field) == com.sporez.actualist.core.rules.RuleValueKind.DATE && !RuleEditorFields.isValueless(op) &&
                            !com.sporez.actualist.core.rules.RuleDateValues.allowsPartial(op) &&
                            com.sporez.actualist.core.rules.RuleDateValues.precision(line.value) != com.sporez.actualist.core.rules.RuleDatePrecision.DAY)
                    set(line.copy(op = op, value = if (reshaped) "" else line.value))
                },
                onDismiss = { picking = null },
            )
            "value" -> PickerDialog(
                title = RuleEditorFields.label(line.field),
                options = when (line.field) {
                    "payee" -> loaded.payees
                    "category" -> loaded.categories
                    "category_group" -> loaded.categoryGroups
                    "account" -> loaded.accounts
                    "cleared", "reconciled" -> RuleEditorFields.booleanOptions(line.field)
                    else -> emptyList()
                },
                searchable = true,
                onPick = { option ->
                    val id = option.id ?: ""
                    if (RuleEditorFields.isMulti(line.op)) {
                        // Each pick adds or removes one id from the list.
                        val ids = RuleEditorFields.multiValues(line.field, line.value)
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
                    TextButton(onClick = { onPick("op") }, enabled = enabled, modifier = Modifier.testTag("rule-$kind-$index-op")) { Text(RuleEditorFields.opLabel(line.op, line.field)) }
                } else if (line.op == RuleEditorFields.DELETE_OP) {
                    // The field button above already says "Delete transaction".
                } else if (line.field == "notes") {
                    TextButton(onClick = { onPick("op") }, enabled = enabled, modifier = Modifier.testTag("rule-$kind-$index-op")) { Text(RuleEditorFields.actionOpLabel(line.op)) }
                } else {
                    Text("  set to", modifier = Modifier.padding(horizontal = 8.dp))
                }
                if (onRemove != null && enabled) TextButton(onClick = onRemove) { Text("Remove") }
            }
            if (RuleEditorFields.isValueless(line.op)) {
                // Nothing to choose: the operator is the whole condition.
            } else if (line.op == RuleEditorFields.DELETE_OP) {
                Text("The transaction is removed when this rule matches.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("rule-$kind-$index-delete-note"))
            } else if (picked) {
                OutlinedButton(onClick = { onPick("value") }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("rule-$kind-$index-value")) {
                    Text(when {
                        line.value.isBlank() -> "Choose…"
                        RuleEditorFields.isBoolean(line.field) -> RuleEditorFields.booleanLabel(line.field, line.value)
                        RuleEditorFields.isMulti(line.op) -> RuleEditorFields.multiValues(line.field, line.value).joinToString(", ") { names[it] ?: it } + "  (tap to add or remove)"
                        else -> names[line.value] ?: line.value
                    })
                }
            } else if (valueKind == com.sporez.actualist.core.rules.RuleValueKind.DATE && kind == "condition") {
                RuleDateValueField(line.value, line.op, { onChange(line.copy(value = it)) }, "rule-$kind-$index-text", enabled)
            } else if (valueKind == com.sporez.actualist.core.rules.RuleValueKind.DATE) {
                ScheduleDateField(line.value, { onChange(line.copy(value = it)) }, "rule-$kind-$index-text", label = "Date (YYYY-MM-DD)")
            } else if (RuleEditorFields.isMulti(line.op)) {
                OutlinedTextField(
                    value = line.value,
                    onValueChange = { onChange(line.copy(value = it)) },
                    enabled = enabled,
                    minLines = 2,
                    label = { Text("Values (one per line)") },
                    modifier = Modifier.fillMaxWidth().testTag("rule-$kind-$index-text"),
                )
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
                    label = {
                        Text(when (valueKind) {
                            com.sporez.actualist.core.rules.RuleValueKind.NUMBER -> if (RuleFields.isDirectionalAmountField(line.field)) "Amount (positive)" else "Amount (negative for outflow)"
                            else -> if (line.op == "hasTags" || line.op == "hasAnyTag") "Tags (e.g. #food #weekly)" else "Text"
                        })
                    },
                    modifier = Modifier.fillMaxWidth().testTag("rule-$kind-$index-text"),
                )
            }
        }
    }
}

/** Count and first rows of the existing transactions the draft's conditions match. */
@Composable
private fun MatchPreviewSection(state: RuleMatchState) {
    Text("Matching transactions", style = MaterialTheme.typography.titleMedium)
    when (state) {
        RuleMatchState.Idle -> Text("Complete the conditions to see matches.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("rule-matches-idle"))
        is RuleMatchState.Failed -> ErrorBanner(state.message, Modifier.testTag("rule-matches-error"))
        is RuleMatchState.Ready -> {
            val preview = state.preview
            Text(
                if (preview.totalCount == 0) "No matching transactions" else "${preview.totalCount} matching ${if (preview.totalCount == 1) "transaction" else "transactions"}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("rule-matches-count"),
            )
            preview.transactions.forEach { match ->
                Row(Modifier.fillMaxWidth().testTag("rule-match-${match.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(match.payeeName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text("${match.date} · ${match.categoryName} · ${match.accountName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Text(money(match.amount), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (preview.totalCount > preview.transactions.size) {
                Text("and ${preview.totalCount - preview.transactions.size} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
