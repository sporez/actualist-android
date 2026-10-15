package com.sporez.actualist.android.ui.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sporez.actualist.android.ui.Loadable
import com.sporez.actualist.android.ui.components.ErrorBanner
import com.sporez.actualist.android.ui.components.LoadingBox
import com.sporez.actualist.android.ui.components.LocalSampleValues
import com.sporez.actualist.android.ui.components.MoneyText
import com.sporez.actualist.android.ui.components.NotesHiddenMessage
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.android.ui.reloading
import com.sporez.actualist.core.budget.BudgetMonthCategory
import com.sporez.actualist.core.budget.BudgetTable
import com.sporez.actualist.core.notes.EntityNotes
import com.sporez.actualist.core.notes.NoteTarget
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.templates.TemplateReads
import com.sporez.actualist.core.templates.editor.TemplateDefinition
import com.sporez.actualist.core.transactions.TransactionQuery
import com.sporez.actualist.core.transactions.TransactionRow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val categoryDayFormat = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)

/** What the category month screen shows: the month's figures, the category's note and template, and its transactions in the month. */
data class CategoryMonthState(
    /** Null when the category is not in the budget any more. */
    val category: BudgetMonthCategory?,
    val tracking: Boolean,
    val note: String?,
    /** A short description of the category's template, or "None". */
    val templateSummary: String,
    val rows: List<TransactionRow>,
)

class CategoryMonthViewModel(session: BudgetSession, private val categoryId: String, private val month: String) : ViewModel() {
    val state = reloading(session, MutableStateFlow(Unit)) {
        val budgetMonth = budget.fetchBudgetMonth(month)
        val templates = runCatching { TemplateReads(database).goalDefsRaw() }.getOrDefault(emptyMap())
        CategoryMonthState(
            category = budgetMonth.category(categoryId),
            tracking = budgetMonth.table == BudgetTable.TRACKING,
            note = runCatching { EntityNotes(database).read(NoteTarget.Category(categoryId)) }.getOrNull(),
            templateSummary = templates[categoryId]?.let { templateText(it) } ?: "None",
            rows = ledger.transactions(TransactionQuery(categoryId = categoryId, month = month, limit = 500)),
        )
    }

    private fun templateText(json: String): String {
        val entries = TemplateDefinition.drafts(json, LocalDate.now()) ?: return "Managed in Actual"
        if (entries.isEmpty()) return "None"
        val titles = entries.map { it.kind.title }
        return if (titles.size <= 2) titles.joinToString(", ") else titles.take(2).joinToString(", ") + " +${titles.size - 2}"
    }
}

/**
 * Actualist's category month screen: the month's Budgeted / Spent (Received) / Remaining (Balance) for one category,
 * its rollover switch, template row and note, and only the category's transactions in that month. Tapping a
 * transaction calls [onOpenTransaction] (the editor).
 *
 * [month] is a budget month id such as `2026-10`; [instanceKey] separates view models of different budgets.
 */
@Composable
fun CategoryMonthScreen(session: BudgetSession, categoryId: String, month: String, instanceKey: String = "", onOpenTransaction: (TransactionRow) -> Unit) {
    val model = viewModel(key = "category-month-$categoryId-$month-$instanceKey") { CategoryMonthViewModel(session, categoryId, month) }
    val budget = viewModel(key = "category-month-budget-$categoryId-$month-$instanceKey") { BudgetViewModel(session, month) }
    val state by model.state.collectAsState()
    val message by budget.messages.message.collectAsState()
    var editingTemplate by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("category-month-screen")) {
        when (val current = state) {
            Loadable.Loading -> LoadingBox()
            is Loadable.Failed -> ErrorBanner(current.message, Modifier.padding(16.dp))
            is Loadable.Ready -> {
                val value = current.value
                val category = value.category
                val groupedRows = value.rows.groupBy { it.date }
                LazyColumn(
                    Modifier.fillMaxSize().testTag("category-month-list"),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    message?.let { text ->
                        item(key = "message") {
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clickable { budget.messages.clear() }) {
                                Text(text, Modifier.padding(12.dp).testTag("category-month-message"), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (category != null) {
                        item(key = "summary") { CategoryMonthSummary(category, value, month, budget) { editingTemplate = true } }
                    }
                    if (groupedRows.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                "No transactions in ${monthTitle(month)}.",
                                Modifier.fillMaxWidth().padding(24.dp).testTag("category-month-empty"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    for ((day, dayRows) in groupedRows) {
                        item(key = "day-$day") {
                            Text(
                                day?.format(categoryDayFormat) ?: "No date",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp, top = 6.dp),
                            )
                        }
                        item(key = "rows-$day") {
                            SectionCard {
                                dayRows.forEachIndexed { index, row ->
                                    CategoryMonthRow(row, onOpenTransaction)
                                    if (index < dayRows.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                }
                if (editingTemplate && category != null) {
                    TemplateEditorDialog(category, budget) { editingTemplate = false }
                }
            }
        }
    }
}

@Composable
private fun CategoryMonthSummary(category: BudgetMonthCategory, state: CategoryMonthState, month: String, budget: BudgetViewModel, onOpenTemplate: () -> Unit) {
    val sample = LocalSampleValues.current
    SectionCard(Modifier.testTag("category-month-summary")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(monthTitle(month), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            // Envelope income categories have nothing budgeted; Actual shows what was received.
            if (!(category.isIncome && !state.tracking)) {
                SummaryRow("Budgeted", category.budgeted, "category-month-budgeted")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            SummaryRow(if (category.isIncome) "Received" else "Spent", category.spent, "category-month-spent")
            if (!category.isIncome) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SummaryRow(if (state.tracking) "Balance" else "Remaining", category.balance, "category-month-remaining", colored = true)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Roll over overspending", style = MaterialTheme.typography.bodyLarge)
                        Text("Negative balances carry into next month", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = category.carryover, onCheckedChange = { budget.setCarryover(category.id, it) }, modifier = Modifier.testTag("category-month-rollover"))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !sample, onClick = onOpenTemplate).padding(vertical = 10.dp).testTag("category-month-template"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Template", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (sample) "Hidden in Sample Values" else state.templateSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (sample) {
                NotesHiddenMessage()
            } else if (!state.note.isNullOrBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Category Note", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                MarkdownText(state.note, Modifier.fillMaxWidth().testTag("category-month-note"), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, amount: Long, tag: String, colored: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(amount, colored = colored, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun CategoryMonthRow(row: TransactionRow, onOpen: (TransactionRow) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable { onOpen(row) }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("transaction-${row.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.displayPayee.ifBlank { "No payee" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            MoneyText(row.amount, colored = true)
        }
        val detail = buildList {
            add(row.accountName)
            row.notes?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
