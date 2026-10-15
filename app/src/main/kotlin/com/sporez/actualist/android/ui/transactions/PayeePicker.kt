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
import com.sporez.actualist.core.transactions.Payee

/** One line of the picker: a section header, "No payee", or a payee. */
internal data class PayeePickerEntry(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val payeeId: String? = null,
    val header: Boolean = false,
)

/**
 * The picker's list for [query]: with no query "No payee", then the recently used payees, then every
 * other payee alphabetically; with a query the matches only, recent ones first. Transfers to
 * [currentAccountId] are left out (a transfer to the same account is meaningless).
 */
internal fun payeePickerEntries(
    payees: List<Payee>,
    recentIds: List<String>,
    currentAccountId: String?,
    query: String,
): List<PayeePickerEntry> {
    val usable = payees.filter { !it.isTransfer || it.transferAccountId != currentAccountId }
    val byId = usable.associateBy { it.id }
    fun entry(payee: Payee) = PayeePickerEntry(
        key = "pick-${payee.id}",
        title = if (payee.isTransfer) "Transfer: ${payee.name}" else payee.name,
        subtitle = if (payee.isTransfer) "Move money between accounts" else null,
        payeeId = payee.id,
    )
    val recents = recentIds.mapNotNull { byId[it] }
    val text = query.trim()
    if (text.isEmpty()) {
        val recentSet = recents.map { it.id }.toSet()
        val others = usable.filter { it.id !in recentSet }.sortedBy { it.name.lowercase() }
        return buildList {
            add(PayeePickerEntry("pick-none", "No payee"))
            if (recents.isNotEmpty()) {
                add(PayeePickerEntry("header-recent", "Recent", header = true))
                recents.forEach { add(entry(it)) }
                if (others.isNotEmpty()) add(PayeePickerEntry("header-all", "All payees", header = true))
            }
            others.forEach { add(entry(it)) }
        }
    }
    val matches = usable.filter { it.name.contains(text, ignoreCase = true) }
    val recentRank = recentIds.withIndex().associate { it.value to it.index }
    return matches
        .sortedWith(compareBy<Payee> { recentRank[it.id] ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() })
        .map(::entry)
}

/** Actualist's payee picker: search, recent payees first, "No payee", and creating a payee from the search text. */
@Composable
fun PayeePickerDialog(
    payees: List<Payee>,
    recentIds: List<String>,
    currentAccountId: String?,
    onPick: (String?) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val entries = payeePickerEntries(payees, recentIds, currentAccountId, query)
    val canCreate = query.isNotBlank() && payees.none { !it.isTransfer && it.name.equals(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Payee") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth().testTag("picker-search"),
                )
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    if (canCreate) {
                        item(key = "create") {
                            Text(
                                "Create payee \"${query.trim()}\"",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().clickable { onCreate(query.trim()) }.padding(vertical = 14.dp).testTag("picker-create"),
                            )
                            HorizontalDivider()
                        }
                    }
                    items(entries, key = { it.key }) { entry ->
                        if (entry.header) {
                            Text(
                                entry.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp).testTag(entry.key),
                            )
                        } else {
                            Column(Modifier.fillMaxWidth().clickable { onPick(entry.payeeId) }.padding(vertical = 12.dp).testTag("pick-${entry.payeeId ?: "none"}")) {
                                Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                                entry.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
