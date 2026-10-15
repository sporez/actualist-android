package com.sporez.actualist.android.ui.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.components.MoneyText
import com.sporez.actualist.android.ui.components.PickerDialog
import com.sporez.actualist.android.ui.components.PickerOption
import com.sporez.actualist.android.ui.components.money
import com.sporez.actualist.android.ui.theme.MoneyTextStyle
import com.sporez.actualist.core.budget.BudgetMonthCategory
import com.sporez.actualist.core.templates.TemplatePreview
import com.sporez.actualist.core.templates.editor.TemplateDefinition
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable
private fun TemplateSummaryLine(label: String, amount: Long) {
    Row {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(amount)
    }
}

/**
 * Preview of the budget's templates for this month, then apply: "Fill Empty" keeps amounts already
 * assigned, "Overwrite" replaces them (Actualist's two apply modes).
 */
@Composable
internal fun TemplatesDialog(viewModel: BudgetViewModel, onDismiss: () -> Unit) {
    var overwrite by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<TemplatePreview?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(overwrite) {
        preview = viewModel.templatePreview(overwrite)
        loaded = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Budget templates") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).testTag("templates-dialog")) {
                val p = preview
                when {
                    !loaded -> Text("Reading templates…")
                    p == null -> Text("No category in this budget has a template. Add one from a category's Template button or the template browser.")
                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = !overwrite, onClick = { overwrite = false }, label = { Text("Fill Empty") }, modifier = Modifier.testTag("templates-fill-empty"))
                            FilterChip(selected = overwrite, onClick = { overwrite = true }, label = { Text("Overwrite") }, modifier = Modifier.testTag("templates-overwrite"))
                        }
                        Text(
                            if (overwrite) "Replaces amounts already assigned this month." else "Only fills categories with nothing assigned yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        p.errors.firstOrNull()?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        val changed = p.categories.filter { it.proposed != it.current }
                        if (changed.isEmpty()) Text("Nothing to change.", style = MaterialTheme.typography.bodySmall)
                        for (category in changed.take(12)) {
                            Row {
                                Text(category.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${money(category.current)} → ${money(category.proposed)}", style = MoneyTextStyle)
                            }
                        }
                        if (changed.size > 12) Text("…and ${changed.size - 12} more", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        if (p.fundingRequired != 0L) TemplateSummaryLine("Funding required", p.fundingRequired)
                        TemplateSummaryLine("Assigned", p.assigned)
                        if (p.released != 0L) TemplateSummaryLine("Released", p.released)
                        if (p.stillNeeded > 0L) TemplateSummaryLine("Still needed", p.stillNeeded)
                        TemplateSummaryLine(if (p.isTracking) "Saved after" else "To Budget after", p.leftover)
                        Row(Modifier.testTag("templates-leftover-change")) {
                            Text(if (p.isTracking) "Total Saved" else "To Budget", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${money(p.availableBefore)} → ${money(p.availableAfter)}", style = MoneyTextStyle)
                        }
                        Row {
                            Text("Categories", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                when (changed.size) {
                                    0 -> "None"
                                    1 -> "1 category"
                                    else -> "${changed.size} categories"
                                },
                                modifier = Modifier.testTag("templates-change-count"),
                            )
                        }
                        if (p.availableAfter < 0L) {
                            Text("Over budget after Apply.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("templates-over-budget"))
                        }
                        if (p.hasNonMoneyUpdates) {
                            Text("Goal targets will also be updated.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("templates-goal-note"))
                        }
                    }
                }
            }
        },
        confirmButton = {
            val p = preview
            TextButton(
                enabled = p != null && p.isReady && p.categories.any { it.proposed != it.current },
                onClick = { viewModel.applyTemplates(overwrite); onDismiss() },
                modifier = Modifier.testTag("templates-apply"),
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun templateSummary(json: String?, today: LocalDate): String {
    val entries = TemplateDefinition.drafts(json, today) ?: return "Managed in Actual"
    if (entries.isEmpty()) return "No template"
    val titles = entries.map { it.kind.title }
    return if (titles.size <= 2) titles.joinToString(", ") else titles.take(2).joinToString(", ") + " +${titles.size - 2}"
}

private data class BrowserRow(val category: BudgetMonthCategory, val group: String, val summary: String)

/**
 * Every category with a template, by group, with the hidden ones collapsed under "Hidden" (Actualist's
 * templates browser); categories without one are offered through "Add template".
 */
@Composable
internal fun TemplatesBrowserDialog(screen: BudgetScreenState, viewModel: BudgetViewModel, onDismiss: () -> Unit) {
    val today = remember { LocalDate.now() }
    var definitions by remember { mutableStateOf<Map<String, String>?>(null) }
    var reload by remember { mutableStateOf(0) }
    var hiddenExpanded by rememberSaveable { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BudgetMonthCategory?>(null) }
    LaunchedEffect(reload) {
        // The save runs in the background; give it a moment before reading the list again.
        if (reload > 0) delay(400)
        definitions = viewModel.templateDefinitions()
    }

    val all = screen.month.categoryGroups.filter { !it.isIncome }.flatMap { group -> group.categories.filter { !it.isIncome }.map { it to group } }
    val loaded = definitions
    val withTemplate = if (loaded == null) emptyList() else all.filter { it.first.id in loaded }
    val without = if (loaded == null) emptyList() else all.filter { it.first.id !in loaded }
    fun browserRow(pair: Pair<BudgetMonthCategory, com.sporez.actualist.core.budget.BudgetMonthCategoryGroup>) =
        BrowserRow(pair.first, pair.second.name, templateSummary(loaded?.get(pair.first.id), today))
    val visible = withTemplate.filter { !it.first.hidden && !it.second.hidden }
    val hidden = withTemplate.filter { it.first.hidden || it.second.hidden }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Templates") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).testTag("templates-browser"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    loaded == null -> Text("Reading templates…")
                    withTemplate.isEmpty() -> Text("No category has a template yet.", modifier = Modifier.testTag("templates-browser-empty"))
                    else -> {
                        for ((groupName, rows) in visible.map(::browserRow).groupBy { it.group }) {
                            Text(groupName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                            for (row in rows) BrowserRowView(row) { editing = row.category }
                        }
                        if (hidden.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { hiddenExpanded = !hiddenExpanded }.padding(top = 10.dp, bottom = 4.dp).testTag("templates-hidden-toggle"),
                            ) {
                                Text("Hidden", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                Text("${hidden.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (hiddenExpanded) for (row in hidden.map(::browserRow)) BrowserRowView(row) { editing = row.category }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { picking = true }, enabled = loaded != null, modifier = Modifier.testTag("templates-add")) { Text("Add template") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("templates-browser-close")) { Text("Close") } },
    )

    if (picking) {
        val visibleOptions = without.filter { !it.first.hidden && !it.second.hidden }
        val hiddenOptions = without.filter { it.first.hidden || it.second.hidden }
        if (visibleOptions.isEmpty() && hiddenOptions.isEmpty()) {
            AlertDialog(
                onDismissRequest = { picking = false },
                title = { Text("No Categories") },
                text = { Text("Every category already has a template.", modifier = Modifier.testTag("templates-picker-empty")) },
                confirmButton = { TextButton(onClick = { picking = false }) { Text("OK") } },
            )
        } else {
            PickerDialog(
                title = "Category",
                options = visibleOptions.map { (c, g) -> PickerOption(c.id, c.name, g.name) } +
                    hiddenOptions.map { (c, g) -> PickerOption(c.id, c.name, "Hidden · ${g.name}") },
                onPick = { option ->
                    picking = false
                    editing = all.firstOrNull { it.first.id == option.id }?.first
                },
                onDismiss = { picking = false },
            )
        }
    }
    editing?.let { category ->
        TemplateEditorDialog(category, viewModel) {
            editing = null
            reload += 1
        }
    }
}

@Composable
private fun BrowserRowView(row: BrowserRow, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp).testTag("template-row-${row.category.id}"),
    ) {
        Text(row.category.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(row.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
