package com.sporez.actualist.android.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.components.BalancePill
import com.sporez.actualist.core.transactions.CategoryOption

/**
 * Actualist's category selection: always searchable, grouped under the category group's
 * header, with an "Uncategorized" choice and "No matching categories" when a search finds none.
 * [onPick] gets the category id, or null for Uncategorized.
 */
@Composable
fun CategoryPickerDialog(
    title: String,
    categories: List<CategoryOption>,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    /** Each category's available balance for the transaction's month; null while loading or unavailable. */
    balances: Map<String, Long>? = null,
    /** The category the transaction has now, marked with a check. */
    selectedId: String? = null,
) {
    var query by remember { mutableStateOf("") }
    val needle = query.trim()
    val matching = if (needle.isEmpty()) categories else categories.filter {
        it.name.contains(needle, ignoreCase = true) || it.groupName.contains(needle, ignoreCase = true)
    }
    val groups = matching.groupBy { it.groupName }
    val showUncategorized = needle.isEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search Categories") },
                    modifier = Modifier.fillMaxWidth().testTag("picker-search"),
                )
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    if (showUncategorized) {
                        item(key = "uncategorized") {
                            Text(
                                "Uncategorized",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 12.dp).testTag("pick-none"),
                            )
                            HorizontalDivider()
                        }
                    }
                    if (matching.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                "No matching categories",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("picker-empty"),
                            )
                        }
                    }
                    for ((group, options) in groups) {
                        item(key = "header-$group") {
                            Text(
                                group,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp).testTag("header-category-$group"),
                            )
                        }
                        items(options, key = { "category-${it.id}" }) { option ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(option.id) }.padding(vertical = 12.dp).testTag("pick-${option.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(option.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                balances?.get(option.id)?.let { available ->
                                    BalancePill(available, Modifier.testTag("category-balance-${option.id}"))
                                }
                                if (option.id == selectedId) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.testTag("category-selected-${option.id}"),
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
