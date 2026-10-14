@file:OptIn(ExperimentalMaterial3Api::class)

package com.sporez.actualist.android.ui.transactions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.components.PickerOption
import com.sporez.actualist.core.query.QueryCondition
import com.sporez.actualist.core.query.QueryDateCondition
import com.sporez.actualist.core.query.QueryDateOperation
import com.sporez.actualist.core.query.QueryDay
import com.sporez.actualist.core.query.QueryIdCondition
import com.sporez.actualist.core.query.QueryIdOperation
import com.sporez.actualist.core.query.QueryJoin
import com.sporez.actualist.core.query.SavedFilter
import com.sporez.actualist.core.query.TransactionFilterDraft
import com.sporez.actualist.core.query.TransactionFilterDraftException
import com.sporez.actualist.core.query.TransactionFilterField
import com.sporez.actualist.core.query.TransactionFilterIdDraft

private val idOperations = listOf(
    QueryIdOperation.IS_EQUAL to "Is",
    QueryIdOperation.IS_NOT_EQUAL to "Is not",
    QueryIdOperation.IS_ONE_OF to "Is one of",
    QueryIdOperation.IS_NOT_ONE_OF to "Is not one of",
)

private val dateOperations = listOf(
    QueryDateOperation.IS to "Is",
    QueryDateOperation.IS_APPROXIMATELY to "Is approximately",
    QueryDateOperation.IS_AFTER to "Is after",
    QueryDateOperation.IS_ON_OR_AFTER to "Is on or after",
    QueryDateOperation.IS_BEFORE to "Is before",
    QueryDateOperation.IS_ON_OR_BEFORE to "Is on or before",
)

/**
 * Actualist's "More Filters" sheet: edit conditions and apply them without saving, reset them,
 * or save them as a named filter. Conditions it cannot author are kept and listed read-only.
 */
@Composable
fun TransactionFilterSheet(viewModel: TransactionsViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initial = remember { viewModel.adhoc.value }
    val startDraft = remember {
        initial?.let { TransactionFilterDraft.from(it.conditions, it.join) } ?: TransactionFilterDraft()
    }
    var draft by remember { mutableStateOf(startDraft) }
    var dateOn by remember { mutableStateOf(startDraft.date != null) }
    var dateOperation by remember { mutableStateOf(startDraft.date?.operation ?: QueryDateOperation.IS) }
    var dateText by remember { mutableStateOf(startDraft.date?.day?.raw ?: java.time.LocalDate.now().toString()) }
    var options by remember { mutableStateOf<Map<TransactionFilterField, List<PickerOption>>>(emptyMap()) }
    var picking by remember { mutableStateOf<TransactionFilterField?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saveName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { options = viewModel.filterOptions() }

    /** The draft with the date fields folded in; throws when the date text is not a day. */
    fun built(): TransactionFilterDraft {
        if (!dateOn) return draft.copy(date = null)
        val day = QueryDay.of(dateText) ?: throw TransactionFilterDraftException("Choose a valid date.")
        return draft.copy(date = QueryDateCondition(dateOperation, day))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 24.dp).testTag("filter-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("More Filters", style = MaterialTheme.typography.titleLarge)
            Text("Refine this transaction feed.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(
                onClick = {
                    draft = draft.reset()
                    dateOn = false
                    error = null
                },
                modifier = Modifier.testTag("filter-sheet-reset"),
            ) { Text("Reset Filters") }

            Text("Match conditions", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.join == QueryJoin.AND,
                    onClick = { draft = draft.copy(join = QueryJoin.AND) },
                    label = { Text("Match All") },
                    modifier = Modifier.testTag("filter-join-all"),
                )
                FilterChip(
                    selected = draft.join == QueryJoin.OR,
                    onClick = { draft = draft.copy(join = QueryJoin.OR) },
                    label = { Text("Match Any") },
                    modifier = Modifier.testTag("filter-join-any"),
                )
            }

            Text("Date", style = MaterialTheme.typography.titleSmall)
            FilterChip(
                selected = dateOn,
                onClick = { dateOn = !dateOn },
                label = { Text("Filter by date") },
                modifier = Modifier.testTag("filter-date-toggle"),
            )
            if (dateOn) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((operation, label) in dateOperations) {
                        FilterChip(
                            selected = dateOperation == operation,
                            onClick = { dateOperation = operation },
                            label = { Text(label) },
                            modifier = Modifier.testTag("filter-date-op-${operation.raw}"),
                        )
                    }
                }
                OutlinedTextField(
                    dateText,
                    { dateText = it },
                    label = { Text("Date (YYYY-MM-DD)") },
                    singleLine = true,
                    isError = QueryDay.of(dateText) == null,
                    modifier = Modifier.fillMaxWidth().testTag("filter-date-value"),
                )
            }

            for (field in TransactionFilterField.values()) {
                val key = field.name.lowercase()
                val value = draft.field(field)
                val titles = options[field].orEmpty().associate { it.id to it.title }
                val labels = value.ids.sorted().map { titles[it] ?: it } +
                    (if (value.includesNull) listOf(if (field == TransactionFilterField.CATEGORY) "No category" else "No payee") else emptyList())
                Text(field.title, style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((operation, label) in idOperations) {
                        FilterChip(
                            selected = value.operation == operation,
                            onClick = { draft = draft.withField(field, value.copy(operation = operation)) },
                            label = { Text(label) },
                            modifier = Modifier.testTag("filter-op-$key-${operation.raw}"),
                        )
                    }
                }
                OutlinedButton(
                    onClick = { picking = field },
                    modifier = Modifier.fillMaxWidth().testTag("filter-select-$key"),
                ) { Text(if (labels.isEmpty()) "Any ${key}" else labels.joinToString(", ")) }
                if (field != TransactionFilterField.ACCOUNT) {
                    FilterChip(
                        selected = value.includesNull,
                        onClick = { draft = draft.withField(field, value.copy(includesNull = !value.includesNull)) },
                        label = { Text(if (field == TransactionFilterField.CATEGORY) "Uncategorized" else "No payee") },
                        modifier = Modifier.testTag("filter-null-$key"),
                    )
                }
            }

            if (draft.preserved.isNotEmpty()) {
                Text("Existing conditions kept as-is", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("filter-preserved"))
                for (condition in draft.preserved) {
                    Text(preservedSummary(condition, options), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("filter-error")) }

            Text("Save as filter", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                saveName,
                { saveName = it },
                label = { Text("Filter name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("filter-name"),
            )
            OutlinedButton(
                onClick = {
                    try {
                        val result = built()
                        val conditions = result.conditions()
                        if (conditions.isEmpty()) {
                            error = "Choose at least one condition for this saved filter."
                        } else {
                            viewModel.saveFilter(saveName, conditions, result.join)
                            onDismiss()
                        }
                    } catch (failure: TransactionFilterDraftException) {
                        error = failure.message
                    }
                },
                enabled = saveName.isNotBlank(),
                modifier = Modifier.testTag("filter-save"),
            ) { Text("Save as filter") }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("filter-cancel")) { Text("Cancel") }
                Button(
                    onClick = {
                        try {
                            val result = built()
                            viewModel.applyFilter(result.conditions(), result.join)
                            onDismiss()
                        } catch (failure: TransactionFilterDraftException) {
                            error = failure.message
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("filter-apply"),
                ) { Text("Apply") }
            }
        }
    }

    picking?.let { field ->
        FilterOptionsDialog(
            field = field,
            options = options[field].orEmpty(),
            value = draft.field(field),
            onChange = { draft = draft.withField(field, it) },
            onDismiss = { picking = null },
        )
    }
}

/** A multi-select list of accounts, payees or categories (a single choice for "is" and "is not"). */
@Composable
private fun FilterOptionsDialog(
    field: TransactionFilterField,
    options: List<PickerOption>,
    value: TransactionFilterIdDraft,
    onChange: (TransactionFilterIdDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = if (query.isBlank()) options else options.filter { it.title.contains(query, ignoreCase = true) }
    val scalar = value.operation == QueryIdOperation.IS_EQUAL || value.operation == QueryIdOperation.IS_NOT_EQUAL
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(field.title) },
        text = {
            Column {
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("filter-options-search"),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp).testTag("filter-options")) {
                    items(shown, key = { it.id ?: it.title }) { option ->
                        val id = option.id ?: return@items
                        val checked = id in value.ids
                        Row(
                            Modifier.fillMaxWidth().testTag("filter-option-${option.title}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    val ids = when {
                                        checked -> value.ids - id
                                        scalar -> setOf(id)
                                        else -> value.ids + id
                                    }
                                    onChange(value.copy(ids = ids, includesNull = if (scalar && !checked) false else value.includesNull))
                                },
                            )
                            Text(option.title)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("filter-options-done")) { Text("Done") } },
    )
}

private fun preservedSummary(condition: QueryCondition, options: Map<TransactionFilterField, List<PickerOption>>): String {
    fun idText(field: TransactionFilterField, value: QueryIdCondition): String {
        val titles = options[field].orEmpty().associate { it.id to it.title }
        val noun = field.title.lowercase()
        val values = value.values.map { id -> if (id == null) "No $noun" else titles[id] ?: id }
        val text = if (values.isEmpty()) "with no value" else "${TransactionFilterDraft.operationName(value.operation)} ${values.joinToString(", ")}"
        return "${field.title} $text (kept unchanged)"
    }
    return when (condition) {
        is QueryCondition.Date ->
            "Date ${TransactionFilterDraft.operationName(condition.condition.operation)} ${condition.condition.day.raw} (kept unchanged)"
        is QueryCondition.Account -> idText(TransactionFilterField.ACCOUNT, condition.condition)
        is QueryCondition.Payee -> idText(TransactionFilterField.PAYEE, condition.condition)
        is QueryCondition.Category -> idText(TransactionFilterField.CATEGORY, condition.condition)
        is QueryCondition.Transfer -> "${if (condition.isTransfer) "Transfer" else "Non-transfer"} transactions (kept unchanged)"
    }
}

/** Saved-filter management: rename, update with the current conditions, delete after a confirmation. */
@Composable
fun SavedFiltersDialog(
    filters: List<SavedFilter>,
    activeId: String?,
    hasApplied: Boolean,
    viewModel: TransactionsViewModel,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf<SavedFilter?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<SavedFilter?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved Filters") },
        text = {
            if (filters.isEmpty()) {
                Text("No Saved Filters")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp).testTag("saved-filters-list")) {
                    items(filters, key = { it.id }) { filter ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(filter.name.ifEmpty { "Unnamed Filter" }, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (filter.isSupported) {
                                    TransactionFilterDraft.summary(filter.queryConditions?.size ?: 0, filter.queryJoin ?: QueryJoin.AND)
                                } else {
                                    "Contains conditions this version cannot apply"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                if (filter.isSupported) {
                                    TextButton(
                                        onClick = { renaming = filter; renameText = filter.name },
                                        modifier = Modifier.testTag("filter-rename-${filter.id}"),
                                    ) { Text("Rename") }
                                    if (activeId == filter.id && hasApplied) {
                                        TextButton(
                                            onClick = { viewModel.updateFilterWithCurrent(filter.id) },
                                            modifier = Modifier.testTag("filter-update-${filter.id}"),
                                        ) { Text("Update with current conditions") }
                                    }
                                }
                                TextButton(
                                    onClick = { deleting = filter },
                                    modifier = Modifier.testTag("filter-delete-${filter.id}"),
                                ) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("saved-filters-done")) { Text("Done") } },
    )
    renaming?.let { filter ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename Saved Filter") },
            text = {
                Column {
                    Text("Choose a name for this filter.")
                    OutlinedTextField(
                        renameText,
                        { renameText = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("filter-rename-field"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.renameFilter(filter.id, renameText); renaming = null },
                    modifier = Modifier.testTag("filter-rename-confirm"),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { filter ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete Saved Filter?") },
            text = { Text("This removes ${filter.name} from your saved filters.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteFilter(filter.id); deleting = null },
                    modifier = Modifier.testTag("filter-delete-confirm"),
                ) { Text("Delete ${filter.name}") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
