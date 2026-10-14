package com.sporez.actualist.android.ui.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.components.PickerDialog
import com.sporez.actualist.android.ui.components.PickerOption
import com.sporez.actualist.core.budget.BudgetMonthCategory
import com.sporez.actualist.core.budget.BudgetMonthCategoryGroup

/** Rename, reorder, move and delete for one category: the actions behind the category sheet's Manage button. */
@Composable
internal fun CategoryManageDialog(
    category: BudgetMonthCategory,
    screen: BudgetScreenState,
    viewModel: BudgetViewModel,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage ${category.name}") },
        text = {
            Column(Modifier.testTag("category-manage"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = { mode = "rename" }, modifier = Modifier.testTag("manage-rename")) { Text("Rename") }
                TextButton(onClick = { viewModel.moveCategoryBy(category.id, -1); onDismiss() }, modifier = Modifier.testTag("manage-move-up")) { Text("Move up") }
                TextButton(onClick = { viewModel.moveCategoryBy(category.id, 1); onDismiss() }, modifier = Modifier.testTag("manage-move-down")) { Text("Move down") }
                TextButton(onClick = { mode = "group" }, modifier = Modifier.testTag("manage-move-group")) { Text("Move to group…") }
                TextButton(onClick = { mode = "delete" }, modifier = Modifier.testTag("manage-delete")) { Text("Delete") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    when (mode) {
        "rename" -> RenameDialog("Rename category", category.name, onSave = { viewModel.renameCategory(category.id, it); onDismiss() }, onDismiss = { mode = null })
        "group" -> PickerDialog(
            title = "Move to group",
            options = screen.month.categoryGroups
                .filter { it.isIncome == category.isIncome && it.id != category.groupId }
                .map { PickerOption(it.id, it.name) },
            onPick = { option ->
                option.id?.let { viewModel.moveCategoryToGroup(category.id, it) }
                onDismiss()
            },
            onDismiss = { mode = null },
        )
        "delete" -> DeleteFlow(
            name = category.name,
            id = category.id,
            isGroup = false,
            destinations = deleteDestinations(screen, category.isIncome, setOf(category.id)),
            viewModel = viewModel,
            onDismiss = { mode = null },
            onDone = onDismiss,
        )
    }
}

/** Rename, reorder and delete for one category group. */
@Composable
internal fun GroupManageDialog(
    group: BudgetMonthCategoryGroup,
    screen: BudgetScreenState,
    viewModel: BudgetViewModel,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage ${group.name}") },
        text = {
            Column(Modifier.testTag("group-manage"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = { mode = "rename" }, modifier = Modifier.testTag("group-rename")) { Text("Rename group") }
                TextButton(onClick = { viewModel.moveGroupBy(group.id, -1); onDismiss() }, modifier = Modifier.testTag("group-move-up")) { Text("Move group up") }
                TextButton(onClick = { viewModel.moveGroupBy(group.id, 1); onDismiss() }, modifier = Modifier.testTag("group-move-down")) { Text("Move group down") }
                TextButton(onClick = { mode = "delete" }, modifier = Modifier.testTag("group-delete")) { Text("Delete group") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    when (mode) {
        "rename" -> RenameDialog("Rename group", group.name, onSave = { viewModel.renameGroup(group.id, it); onDismiss() }, onDismiss = { mode = null })
        "delete" -> DeleteFlow(
            name = group.name,
            id = group.id,
            isGroup = true,
            destinations = deleteDestinations(screen, group.isIncome, group.categories.map { it.id }.toSet()),
            viewModel = viewModel,
            onDismiss = { mode = null },
            onDone = onDismiss,
        )
    }
}

@Composable
private fun RenameDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("rename-field"))
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("rename-save")) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Categories that can receive a deleted category's activity and budget: same kind, not among [excluded]. */
private fun deleteDestinations(screen: BudgetScreenState, isIncome: Boolean, excluded: Set<String>): List<PickerOption> =
    screen.month.categoryGroups
        .filter { it.isIncome == isIncome }
        .flatMap { group ->
            group.categories
                .filter { it.isIncome == isIncome && it.id !in excluded }
                .map { PickerOption(it.id, it.name, if (it.hidden || group.hidden) "${group.name} · hidden" else group.name) }
        }

/**
 * Deleting asks for a destination only when something would be lost (transactions, or
 * budgeted money), as Actualist does; otherwise a plain confirmation.
 */
@Composable
private fun DeleteFlow(
    name: String,
    id: String,
    isGroup: Boolean,
    destinations: List<PickerOption>,
    viewModel: BudgetViewModel,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    var needsTransfer by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(id) {
        val result = viewModel.needsTransfer(id, isGroup)
        if (result == null) onDismiss() else needsTransfer = result
    }
    fun delete(transferId: String?) {
        if (isGroup) viewModel.deleteGroup(id, transferId) else viewModel.deleteCategory(id, transferId)
        onDone()
    }
    when (needsTransfer) {
        null -> Unit
        false -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (isGroup) "Delete group" else "Delete category") },
            text = { Text("Delete $name? It has no transactions or budgeted money.") },
            confirmButton = { TextButton(onClick = { delete(null) }, modifier = Modifier.testTag("delete-confirm")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        true -> if (destinations.isEmpty()) {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Nowhere to transfer") },
                text = { Text("$name is in use. Add another category to receive its transactions and budget first.") },
                confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            )
        } else {
            PickerDialog(
                title = "Delete $name: move its transactions and budget to",
                options = destinations,
                onPick = { option -> delete(option.id) },
                onDismiss = onDismiss,
                searchable = destinations.size > 8,
            )
        }
    }
}
