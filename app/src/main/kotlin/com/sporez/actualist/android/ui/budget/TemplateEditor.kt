package com.sporez.actualist.android.ui.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.components.AmountField
import com.sporez.actualist.core.budget.BudgetMonthCategory
import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.templates.TemplateCalendar
import com.sporez.actualist.core.templates.editor.TemplateAdjustment
import com.sporez.actualist.core.templates.editor.TemplateAdjustmentDirection
import com.sporez.actualist.core.templates.editor.TemplateAdjustmentMode
import com.sporez.actualist.core.templates.editor.TemplateAuthoringContext
import com.sporez.actualist.core.templates.editor.TemplateAuthoringValidation
import com.sporez.actualist.core.templates.editor.TemplateCadence
import com.sporez.actualist.core.templates.editor.TemplateDraftEntry as TE
import com.sporez.actualist.core.templates.editor.TemplateDraftTransitions
import com.sporez.actualist.core.templates.editor.TemplateEditorDraft
import com.sporez.actualist.core.templates.editor.TemplateEditorKind
import com.sporez.actualist.core.templates.editor.TemplateEditorSnapshot
import com.sporez.actualist.core.templates.editor.TemplateIncomeOption
import com.sporez.actualist.core.templates.editor.TemplateLimitPeriod
import com.sporez.actualist.core.templates.editor.TemplateScheduleOption
import java.time.LocalDate

private data class EditorItem(val id: Int, val entry: TE)

private fun plain(value: Double): String =
    if (value == Math.rint(value) && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()

private fun moneyText(value: Double): String = Money.format(Math.round(value * 100), symbol = "", grouping = false)

private fun parseMoney(text: String): Double? = Money.parse(text)?.let { it / 100.0 }

private fun parseNumber(text: String): Double? = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

private fun parseCount(text: String): Int? = text.trim().toIntOrNull()

/** The editor's state and edits, handed to the entry cards so they stay plain composables. */
private class EditorOps(
    val today: LocalDate,
    val entries: List<TE>,
    val schedules: List<TemplateScheduleOption>,
    val incomes: List<TemplateIncomeOption>,
    val text: (Int, String, String) -> String,
    val isBad: (Int, String) -> Boolean,
    /** Stores the typed text and applies it to the entry; a null result marks the field as unfinished. */
    val input: (Int, String, String, (String, TE) -> TE?) -> Unit,
    val update: (Int, (TE) -> TE) -> Unit,
    val clear: (Int, String) -> Unit,
    val retype: (Int, TemplateEditorKind) -> Unit,
    val remove: (Int) -> Unit,
    val addLimit: () -> Unit,
)

/**
 * The category's template, edited through the ported template editor model as a list of entries
 * (Actual runs them in priority order). Everything Actualist's editor authors is editable here:
 * fixed amounts every day, week, month or year; saving by a date, repeating, with early spending;
 * shares of income; balance limits by day, week or month that can retain funds over the cap; refills;
 * copying or averaging earlier months with an adjustment; covering a schedule (save up or cover each
 * occurrence); remainders; goals; and a note on every entry. Actualist's validation refuses whatever
 * Actual would reject.
 */
@Composable
internal fun TemplateEditorDialog(category: BudgetMonthCategory, viewModel: BudgetViewModel, onDismiss: (saved: Boolean) -> Unit) {
    val today = remember { LocalDate.now() }
    var snapshot by remember { mutableStateOf<TemplateEditorSnapshot?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<EditorItem>>(emptyList()) }
    var nextId by remember { mutableStateOf(0) }
    val texts = remember { mutableStateMapOf<String, String>() }
    var bad by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun key(id: Int, field: String) = "$id:$field"

    fun clearField(id: Int, field: String) {
        texts.remove(key(id, field))
        bad = bad - key(id, field)
    }

    fun clearAll(id: Int) {
        texts.keys.filter { it.startsWith("$id:") }.forEach { texts.remove(it) }
        bad = bad.filter { !it.startsWith("$id:") }.toSet()
    }

    // A fresh entry starts with empty amount fields, as in Actualist: the entry is unfinished until one is typed.
    fun seedBlank(id: Int, kind: TemplateEditorKind) {
        val field = when (kind) {
            TemplateEditorKind.MONTHLY_FIXED, TemplateEditorKind.DATE_TARGET, TemplateEditorKind.BALANCE_LIMIT, TemplateEditorKind.GOAL -> "amount"
            TemplateEditorKind.PERCENTAGE -> "percent"
            else -> return
        }
        texts[key(id, field)] = ""
        bad = bad + key(id, field)
    }

    fun newItem(kind: TemplateEditorKind): EditorItem {
        val item = EditorItem(nextId, kind.makeDraft(today))
        nextId += 1
        seedBlank(item.id, kind)
        return item
    }

    LaunchedEffect(category.id) {
        val loadedSnapshot = viewModel.templateEditorSnapshot(category.id)
        snapshot = loadedSnapshot
        if (loadedSnapshot != null && loadedSnapshot.lock.isEditable) {
            val entries = loadedSnapshot.draft.entries
            if (entries.isEmpty()) {
                items = listOf(newItem(TemplateEditorKind.MONTHLY_FIXED))
            } else {
                items = entries.mapIndexed { index, entry -> EditorItem(nextId + index, entry) }
                nextId += entries.size
            }
        }
        loaded = true
    }

    val current = snapshot
    val entries = items.map { it.entry }
    val context = TemplateAuthoringContext(today, current?.schedules ?: emptyList(), current?.incomeCategories ?: emptyList())
    val issues = if (items.isEmpty()) emptyList() else TemplateAuthoringValidation.issues(entries, context)

    val ops = EditorOps(
        today = today,
        entries = entries,
        schedules = context.schedules,
        incomes = context.incomeCategories,
        text = { id, field, fallback -> texts[key(id, field)] ?: fallback },
        isBad = { id, field -> key(id, field) in bad },
        input = { id, field, typed, apply ->
            texts[key(id, field)] = typed
            val existing = items.firstOrNull { it.id == id }
            val updated = if (existing == null) null else apply(typed, existing.entry)
            if (updated == null) {
                bad = bad + key(id, field)
            } else {
                bad = bad - key(id, field)
                items = items.map { if (it.id == id) it.copy(entry = updated) else it }
            }
        },
        update = { id, change -> items = items.map { if (it.id == id) it.copy(entry = change(it.entry)) else it } },
        clear = { id, field -> clearField(id, field) },
        retype = { id, kind ->
            items = items.map { if (it.id == id) it.copy(entry = it.entry.retyped(kind, today)) else it }
            clearAll(id)
            seedBlank(id, kind)
        },
        remove = { id ->
            items = items.filter { it.id != id }
            clearAll(id)
        },
        addLimit = { items = items + newItem(TemplateEditorKind.BALANCE_LIMIT) },
    )

    AlertDialog(
        onDismissRequest = { onDismiss(false) },
        title = { Text("Template for ${category.name}") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).testTag("template-editor"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    !loaded -> Text("Reading the template…")
                    current == null -> Text("This template could not be read. Edit it in Actual.")
                    !current.lock.isEditable -> Text(current.lock.reasonText ?: "This template is managed in Actual. Edit it there.", modifier = Modifier.testTag("template-locked"))
                    else -> {
                        items.forEachIndexed { index, item ->
                            TemplateEntryCard(index, item, ops)
                        }
                        issues.firstOrNull()?.let {
                            Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("template-issue"))
                        }
                        TextButton(
                            onClick = {
                                val kind = if (TemplateEditorKind.GOAL in TemplateDraftTransitions.addableKinds(entries)) TemplateEditorKind.GOAL else TemplateEditorKind.MONTHLY_FIXED
                                items = items + newItem(kind)
                            },
                            modifier = Modifier.testTag("template-add-entry"),
                        ) { Text("Add another entry") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = loaded && current?.lock?.isEditable == true && items.isNotEmpty() && bad.isEmpty() && issues.isEmpty(),
                onClick = {
                    viewModel.saveTemplate(category.id, TemplateEditorDraft(entries))
                    onDismiss(true)
                },
                modifier = Modifier.testTag("template-save"),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (current != null && current.lock.isEditable && current.draft.entries.isNotEmpty()) {
                    TextButton(
                        onClick = { viewModel.saveTemplate(category.id, TemplateEditorDraft(emptyList())); onDismiss(true) },
                        modifier = Modifier.testTag("template-remove"),
                    ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = { onDismiss(false) }) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Choices(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun Choice(selected: Boolean, label: String, tag: String, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = Modifier.testTag(tag))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TemplateEntryCard(index: Int, item: EditorItem, ops: EditorOps) {
    // Field tags keep their single-entry names for the first entry, so existing tests still apply.
    fun tag(name: String) = if (index == 0) name else "$name-$index"
    val id = item.id
    val entry = item.entry

    @Composable
    fun Field(field: String, current: String, label: String, name: String, keyboard: KeyboardType = KeyboardType.Number, apply: (String, TE) -> TE?) {
        OutlinedTextField(
            value = ops.text(id, field, current),
            onValueChange = { ops.input(id, field, it, apply) },
            label = { Text(label) },
            singleLine = true,
            isError = ops.isBad(id, field),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth().testTag(tag(name)),
        )
    }

    @Composable
    fun Amount(field: String, current: Double, label: String, name: String, apply: (Double, TE) -> TE?) {
        AmountField(
            ops.text(id, field, moneyText(current)),
            { typed -> ops.input(id, field, typed) { t, e -> parseMoney(t)?.let { apply(it, e) } } },
            label = label,
            tag = tag(name),
        )
    }

    @Composable
    fun Priority(value: Int) {
        Field("priority", value.toString(), "Priority", "template-priority") { t, e ->
            val n = parseCount(t)
            if (n == null) null else when {
                e is TE.MonthlyFixed -> e.copy(priority = n)
                e is TE.DateTarget -> e.copy(priority = n)
                e is TE.Percentage -> e.copy(priority = n)
                e is TE.Refill -> e.copy(priority = n)
                e is TE.Copy -> e.copy(priority = n)
                e is TE.Average -> e.copy(priority = n)
                e is TE.Schedule -> e.copy(priority = n)
                else -> null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (index > 0) HorizontalDivider()
        Text(entry.kind.title, style = MaterialTheme.typography.titleSmall)
        Choices {
            for (kind in TemplateDraftTransitions.typeChangeKinds(ops.entries, index)) {
                Choice(entry.kind == kind, kind.title, tag("template-kind-${kind.raw}")) { ops.retype(id, kind) }
            }
        }
        when (entry) {
            is TE.MonthlyFixed -> {
                Amount("amount", entry.amount, "Amount", "template-amount") { v, e -> if (e is TE.MonthlyFixed) e.copy(amount = v) else null }
                Field("interval", entry.interval.toString(), "Every", "template-interval") { t, e ->
                    val n = parseCount(t)
                    if (n != null && e is TE.MonthlyFixed) e.copy(interval = n) else null
                }
                Choices {
                    for (cadence in TemplateCadence.values()) {
                        Choice(entry.cadence == cadence, cadence.raw.replaceFirstChar { it.uppercase() }, tag("template-cadence-${cadence.raw}")) {
                            ops.update(id) { e -> if (e is TE.MonthlyFixed) e.copy(cadence = cadence) else e }
                        }
                    }
                }
                Field("starting", entry.starting, "Starting date (YYYY-MM-DD)", "template-starting", KeyboardType.Text) { t, e ->
                    val day = t.trim()
                    if (e is TE.MonthlyFixed && TemplateCalendar.validatedDate(day) != null) e.copy(starting = day) else null
                }
                Priority(entry.priority)
            }
            is TE.DateTarget -> {
                Amount("amount", entry.amount, "Total", "template-amount") { v, e -> if (e is TE.DateTarget) e.copy(amount = v) else null }
                Field("month", entry.month, "Target month (YYYY-MM)", "template-month", KeyboardType.Text) { t, e ->
                    if (e is TE.DateTarget && TemplateDraftTransitions.validMonth(t)) e.copy(month = t.trim()) else null
                }
                ToggleRow("Repeats", entry.repeatInterval != null, tag("template-repeats")) { on ->
                    if (!on) ops.clear(id, "repeatInterval")
                    ops.update(id) { e -> if (e is TE.DateTarget) TemplateDraftTransitions.setRepeats(e, on) else e }
                }
                if (entry.repeatInterval != null) {
                    Field("repeatInterval", entry.repeatInterval.toString(), "Repeat every", "template-repeat-interval") { t, e ->
                        val n = parseCount(t)
                        if (n != null && e is TE.DateTarget) e.copy(repeatInterval = n) else null
                    }
                    Choices {
                        Choice(!entry.annual, "Months", tag("template-period-months")) {
                            ops.update(id) { e -> if (e is TE.DateTarget) TemplateDraftTransitions.setAnnual(e, false) else e }
                        }
                        Choice(entry.annual, "Years", tag("template-period-years")) {
                            ops.update(id) { e -> if (e is TE.DateTarget) TemplateDraftTransitions.setAnnual(e, true) else e }
                        }
                    }
                }
                ToggleRow("Allow early spending", entry.isSpend, tag("template-early-spending")) { on ->
                    if (!on) ops.clear(id, "spendStart")
                    ops.update(id) { e -> if (e is TE.DateTarget) TemplateDraftTransitions.setEarlySpending(e, on) else e }
                }
                if (entry.isSpend) {
                    Field("spendStart", entry.fromMonth ?: "", "Start spending in (YYYY-MM)", "template-spend-start", KeyboardType.Text) { t, e ->
                        if (e is TE.DateTarget && TemplateDraftTransitions.validMonth(t)) e.copy(fromMonth = t.trim()) else null
                    }
                }
                Priority(entry.priority)
                Hint("Repeating targets can be anchored in a past month.")
            }
            is TE.Percentage -> {
                PercentageSource(entry, id, ops, ::tag)
                Field("percent", plain(entry.percent), "Percentage", "template-percent") { t, e ->
                    val v = parseNumber(t)
                    if (v != null && v > 0 && e is TE.Percentage) e.copy(percent = v) else null
                }
                Choices {
                    Choice(!entry.previous, "This month", tag("template-previous-false")) {
                        ops.update(id) { e -> if (e is TE.Percentage) TemplateDraftTransitions.setPercentagePrevious(e, false) else e }
                    }
                    Choice(entry.previous, "Last month", tag("template-previous-true")) {
                        ops.update(id) { e -> if (e is TE.Percentage) TemplateDraftTransitions.setPercentagePrevious(e, true) else e }
                    }
                }
                Priority(entry.priority)
                Hint("Percentage templates use income from the selected period. Available funds can only use this month.")
            }
            is TE.BalanceLimit -> {
                val fallbackStart = TemplateDraftTransitions.defaultWeeklyStart(ops.entries, ops.today)
                Amount("amount", entry.amount, "Amount", "template-amount") { v, e -> if (e is TE.BalanceLimit) e.copy(amount = v) else null }
                Choices {
                    for ((period, label) in listOf(TemplateLimitPeriod.DAILY to "Day", TemplateLimitPeriod.WEEKLY to "Week", TemplateLimitPeriod.MONTHLY to "Month")) {
                        Choice(entry.period == period, label, tag("template-limit-period-${period.raw}")) {
                            ops.update(id) { e -> if (e is TE.BalanceLimit) TemplateDraftTransitions.setLimitPeriod(e, period, fallbackStart) else e }
                        }
                    }
                }
                if (entry.period == TemplateLimitPeriod.WEEKLY) {
                    Field("limitStart", entry.start ?: "", "Starting date (YYYY-MM-DD)", "template-limit-start", KeyboardType.Text) { t, e ->
                        val day = t.trim()
                        if (e is TE.BalanceLimit && TemplateCalendar.validatedDate(day) != null) e.copy(start = day) else null
                    }
                    val weekday = TemplateDraftTransitions.limitWeekday(entry, fallbackStart)
                    Choices {
                        for (day in 1..7) {
                            Choice(weekday == day, TemplateDraftTransitions.weekdayName(day).take(3), tag("template-weekday-$day")) {
                                ops.clear(id, "limitStart")
                                ops.update(id) { e -> if (e is TE.BalanceLimit) TemplateDraftTransitions.setLimitWeekday(e, day, fallbackStart) else e }
                            }
                        }
                    }
                }
                ToggleRow("Retain existing funds over the cap", entry.hold, tag("template-limit-hold")) { on ->
                    ops.update(id) { e -> if (e is TE.BalanceLimit) e.copy(hold = on) else e }
                }
                Hint("Weekly and daily caps change with the number of days or weeks in each month.")
            }
            is TE.Refill -> {
                Priority(entry.priority)
                if (ops.entries.none { it is TE.BalanceLimit }) {
                    Hint("Add a Balance Limit to set the refill target.")
                    TextButton(onClick = ops.addLimit, modifier = Modifier.testTag(tag("template-add-limit"))) { Text("Add Balance Limit") }
                }
            }
            is TE.Copy -> {
                Field("lookBack", entry.lookBack.toString(), "Number of months back", "template-months") { t, e ->
                    val n = parseCount(t)
                    if (n != null && e is TE.Copy) e.copy(lookBack = n) else null
                }
                Hint("Budget what was budgeted that many months ago. Use the Average type to average earlier spending instead.")
                Priority(entry.priority)
            }
            is TE.Average -> {
                Field("numMonths", entry.numMonths.toString(), "Number of months back", "template-months") { t, e ->
                    val n = parseCount(t)
                    if (n != null && e is TE.Average) e.copy(numMonths = n) else null
                }
                AdjustmentFields(id, entry, ops, ::tag)
                Priority(entry.priority)
            }
            is TE.Schedule -> {
                ScheduleFields(id, entry, ops, ::tag)
                Priority(entry.priority)
            }
            is TE.Remainder -> {
                Field("weight", plain(entry.weight), "Weight", "template-weight") { t, e ->
                    val v = parseNumber(t)
                    if (v != null && e is TE.Remainder) e.copy(weight = v) else null
                }
            }
            is TE.Goal -> {
                Amount("amount", entry.amount, "Target", "template-amount") { v, e -> if (e is TE.Goal) e.copy(amount = v) else null }
                Hint("A balance goal shown on the category; it does not assign money by itself.")
            }
        }
        val noteText = ops.text(id, "note", entry.description ?: "")
        OutlinedTextField(
            value = noteText,
            onValueChange = { typed ->
                ops.input(id, "note", typed) { t, e -> e.updatingDescription(t) }
            },
            label = { Text("Note") },
            modifier = Modifier.fillMaxWidth().testTag(tag("template-note")),
        )
        TextButton(
            enabled = noteText.isNotEmpty(),
            onClick = {
                ops.input(id, "note", "") { t, e -> e.updatingDescription(t) }
            },
            modifier = Modifier.testTag(tag("template-clear-note")),
        ) { Text("Clear Note", color = MaterialTheme.colorScheme.error) }
        if (ops.entries.size > 1 || index > 0) {
            TextButton(onClick = { ops.remove(id) }, modifier = Modifier.testTag(tag("template-remove-entry"))) { Text("Remove this entry") }
        }
    }
}

@Composable
private fun PercentageSource(entry: TE.Percentage, id: Int, ops: EditorOps, tag: (String) -> String) {
    val source = entry.sourceCategory.trim()
    val options = mutableListOf(TemplateIncomeOption(TemplateDraftTransitions.ALL_INCOME, "All income"))
    if (!entry.previous) options += TemplateIncomeOption(TemplateDraftTransitions.AVAILABLE_FUNDS, "Available funds")
    options += ops.incomes.filter { it.isAvailable }
    val selected = options.firstOrNull { it.id == source }
        ?: options.firstOrNull { it.name.equals(source, ignoreCase = true) }
    Text("Income source", style = MaterialTheme.typography.labelLarge)
    for (option in options) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable {
                ops.update(id) { e -> if (e is TE.Percentage) e.copy(sourceCategory = option.id) else e }
            }.testTag(tag("template-source-${option.id}")),
        ) {
            RadioButton(selected = selected?.id == option.id, onClick = {
                ops.update(id) { e -> if (e is TE.Percentage) e.copy(sourceCategory = option.id) else e }
            })
            Text(option.name)
        }
    }
    if (selected == null && source.isNotEmpty()) Hint("$source (unavailable)")
}

@Composable
private fun ScheduleFields(id: Int, entry: TE.Schedule, ops: EditorOps, tag: (String) -> String) {
    val selection = entry.scheduleId ?: ops.schedules.firstOrNull { it.name == entry.name }?.id
    if (ops.schedules.isEmpty()) {
        Hint("No schedules to cover. Create a schedule before saving this template.")
    } else {
        Text("Schedule", style = MaterialTheme.typography.labelLarge)
        for (option in ops.schedules) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable {
                    ops.update(id) { e -> if (e is TE.Schedule) TemplateDraftTransitions.setSchedule(e, option) else e }
                }.testTag(tag("template-schedule-${option.id}")),
            ) {
                RadioButton(selected = selection == option.id, onClick = {
                    ops.update(id) { e -> if (e is TE.Schedule) TemplateDraftTransitions.setSchedule(e, option) else e }
                })
                Text(option.name)
            }
        }
        if (selection == null && entry.name.isNotBlank()) Hint("${entry.name} (unavailable)")
        Choices {
            Choice(!entry.full, "Save up for the next occurrence", tag("template-schedule-save-up")) {
                ops.update(id) { e -> if (e is TE.Schedule) e.copy(full = false) else e }
            }
            Choice(entry.full, "Cover each occurrence when it occurs", tag("template-schedule-cover")) {
                ops.update(id) { e -> if (e is TE.Schedule) e.copy(full = true) else e }
            }
        }
        AdjustmentFields(id, entry, ops, tag)
    }
}

@Composable
private fun AdjustmentFields(id: Int, entry: TE, ops: EditorOps, tag: (String) -> String) {
    val mode = TemplateDraftTransitions.adjustmentMode(entry)
    Text("Adjustment", style = MaterialTheme.typography.labelLarge)
    Choices {
        for (option in TemplateAdjustmentMode.values()) {
            Choice(mode == option, option.title, tag("template-adjustment-${option.name.lowercase()}")) {
                ops.clear(id, "adjustment")
                ops.update(id) { e -> TemplateDraftTransitions.setAdjustmentMode(e, option) }
            }
        }
    }
    if (mode == TemplateAdjustmentMode.NONE) return
    if (mode == TemplateAdjustmentMode.PERCENT) {
        val direction = TemplateDraftTransitions.adjustmentDirection(entry)
        Choices {
            for (option in TemplateAdjustmentDirection.values()) {
                Choice(direction == option, option.title, tag("template-adjustment-${option.name.lowercase()}")) {
                    ops.clear(id, "adjustment")
                    ops.update(id) { e -> TemplateDraftTransitions.setAdjustmentDirection(e, option) }
                }
            }
        }
    }
    val value = entry.modifierAdjustment?.value ?: 0.0
    val shown = if (entry.modifierAdjustment is TemplateAdjustment.Percent) Math.abs(value) else value
    OutlinedTextField(
        value = ops.text(id, "adjustment", plain(shown)),
        onValueChange = { typed -> ops.input(id, "adjustment", typed) { t, e -> parseNumber(t)?.let { TemplateDraftTransitions.setAdjustmentValue(e, it) } } },
        label = { Text(if (mode == TemplateAdjustmentMode.PERCENT) "Percentage" else "Fixed amount") },
        singleLine = true,
        isError = ops.isBad(id, "adjustment"),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag("template-adjustment")),
    )
}
