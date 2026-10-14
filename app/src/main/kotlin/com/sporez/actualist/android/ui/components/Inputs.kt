package com.sporez.actualist.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sporez.actualist.core.money.Money

/** A money input. [onValid] receives minor units, or null while the text is not a valid amount. */
@Composable
fun AmountField(
    text: String,
    onTextChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    tag: String = "amount-field",
) {
    val valid = text.isBlank() || Money.parse(text) != null
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        label = { Text(label) },
        singleLine = true,
        isError = !valid,
        supportingText = { if (!valid) Text("Enter an amount like 12.34") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth().testTag(tag),
    )
}

data class PickerOption(val id: String?, val title: String, val subtitle: String? = null)

/** A searchable single-choice list in a dialog; safer than dropdown menus on small screens. */
@Composable
fun PickerDialog(
    title: String,
    options: List<PickerOption>,
    onPick: (PickerOption) -> Unit,
    onDismiss: () -> Unit,
    searchable: Boolean = options.size > 8,
    extraAction: Pair<String, (String) -> Unit>? = null,
) {
    var query by remember { mutableStateOf("") }
    val filtered = if (query.isBlank()) options else options.filter { it.title.contains(query, ignoreCase = true) || it.subtitle?.contains(query, ignoreCase = true) == true }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (searchable || extraAction != null) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text("Search") },
                        modifier = Modifier.fillMaxWidth().testTag("picker-search"),
                    )
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    if (extraAction != null && query.isNotBlank() && options.none { it.title.equals(query.trim(), ignoreCase = true) }) {
                        item {
                            Text(
                                "${extraAction.first} \"${query.trim()}\"",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().clickable { extraAction.second(query.trim()) }.padding(vertical = 14.dp).testTag("picker-create"),
                            )
                            HorizontalDivider()
                        }
                    }
                    items(filtered, key = { (it.id ?: "null") + it.title }) { option ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(option) }.padding(vertical = 12.dp).testTag("pick-${option.id ?: "none"}")) {
                            Text(option.title, style = MaterialTheme.typography.bodyLarge)
                            option.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
