package com.sporez.actualist.android.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
                            Text(
                                option.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().clickable { onPick(option.id) }.padding(vertical = 12.dp).testTag("pick-${option.id}"),
                            )
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
