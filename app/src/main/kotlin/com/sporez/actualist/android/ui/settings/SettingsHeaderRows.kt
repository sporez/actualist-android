package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sporez.actualist.core.server.CustomHeader
import com.sporez.actualist.core.server.CustomHeaderException
import com.sporez.actualist.core.server.EndpointHeaders

/** One header row being edited; [revealed] shows its value instead of dots. */
data class HeaderDraft(val name: String = "", val value: String = "", val revealed: Boolean = false) {
    companion object {
        /** Rows for saved `Name: value` lines; an unreadable set yields no rows. */
        fun fromText(text: String): List<HeaderDraft> =
            runCatching { EndpointHeaders.parse(text).map { HeaderDraft(it.name, it.value) } }.getOrDefault(emptyList())

        /** `Name: value` lines for the non-blank rows, as the connection saves them. */
        fun toText(rows: List<HeaderDraft>): String =
            rows.filter { it.name.isNotBlank() || it.value.isNotBlank() }.joinToString("\n") { "${it.name.trim()}: ${it.value.trim()}" }

        /** The saved `Name: value` lines as headers; an unreadable set yields none. */
        fun validatedFromText(text: String): List<CustomHeader> =
            runCatching { EndpointHeaders.parse(text) }.getOrDefault(emptyList())

        /** The validated headers of [rows] (blank rows skipped); throws [CustomHeaderException] with Actualist's wording. */
        fun validated(rows: List<HeaderDraft>): List<CustomHeader> =
            EndpointHeaders.validate(rows.filter { it.name.isNotBlank() || it.value.isNotBlank() }.map { CustomHeader(it.name, it.value) })
    }
}

/**
 * Actualist's custom headers editor: a name and a value per header, values hidden until revealed,
 * Add Header and remove. Tags are indexed: header-name-N, header-value-N, header-reveal-N,
 * header-remove-N, add-header.
 */
@Composable
fun HeaderRowsEditor(rows: List<HeaderDraft>, onChange: (List<HeaderDraft>) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { index, row ->
            fun update(changed: HeaderDraft) = onChange(rows.mapIndexed { i, existing -> if (i == index) changed else existing })
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = row.name,
                    onValueChange = { update(row.copy(name = it)) },
                    label = { Text("Header name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("header-name-$index"),
                )
                OutlinedTextField(
                    value = row.value,
                    onValueChange = { update(row.copy(value = it)) },
                    label = { Text("Header value") },
                    singleLine = true,
                    visualTransformation = if (row.revealed) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("header-value-$index"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { update(row.copy(revealed = !row.revealed)) }, modifier = Modifier.testTag("header-reveal-$index")) {
                        Text(if (row.revealed) "Hide Header Value" else "Show Header Value")
                    }
                    TextButton(
                        onClick = { onChange(rows.filterIndexed { i, _ -> i != index }) },
                        modifier = Modifier.testTag("header-remove-$index"),
                    ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        TextButton(onClick = { onChange(rows + HeaderDraft()) }, modifier = Modifier.testTag("add-header")) { Text("Add Header") }
    }
}
