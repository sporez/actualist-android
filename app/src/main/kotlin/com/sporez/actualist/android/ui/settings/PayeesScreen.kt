package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sporez.actualist.android.ui.Loadable
import com.sporez.actualist.android.ui.MessageState
import com.sporez.actualist.android.ui.components.ErrorBanner
import com.sporez.actualist.android.ui.components.LoadingBox
import com.sporez.actualist.android.ui.components.PickerDialog
import com.sporez.actualist.android.ui.components.PickerOption
import com.sporez.actualist.android.ui.reloading
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.transactions.PayeeUsage
import com.sporez.actualist.core.transactions.PayeeWrites
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class PayeesViewModel(private val session: BudgetSession) : ViewModel() {
    val messages = MessageState()
    val payees = reloading(session, MutableStateFlow(Unit)) { PayeeWrites(database).usage() }

    /** Budget-wide learning switch and the payees with learning off (Actual's category learning). */
    val learning = reloading(session, MutableStateFlow(Unit)) {
        val learning = com.sporez.actualist.core.rules.CategoryLearning(database)
        learning.enabled() to learning.payeesNotLearning()
    }

    fun setLearning(enabled: Boolean) = write(if (enabled) "Category learning on" else "Category learning off") {
        com.sporez.actualist.core.rules.CategoryLearning(database).setEnabled(enabled)
    }

    fun setPayeeLearns(id: String, learns: Boolean) = write("Saved") { com.sporez.actualist.core.rules.CategoryLearning(database).setPayeeLearns(id, learns) }

    fun rename(id: String, name: String) = write("Renamed") { PayeeWrites(database).rename(id, name) }

    fun merge(source: String, target: String) = write("Merged") { PayeeWrites(database).merge(setOf(source), target) }

    private fun write(done: String, block: BudgetSession.() -> Any?) {
        viewModelScope.launch {
            try {
                session.write { block() }
                messages.show(done)
            } catch (error: Exception) {
                messages.show(error.message ?: "The change could not be saved.")
            }
        }
    }
}

@Composable
fun PayeesScreen(viewModel: PayeesViewModel) {
    val state by viewModel.payees.collectAsState()
    val message by viewModel.messages.message.collectAsState()
    val learningState by viewModel.learning.collectAsState()
    val learning = (learningState as? Loadable.Ready)?.value
    var selected by remember { mutableStateOf<PayeeUsage?>(null) }
    var merging by remember { mutableStateOf<PayeeUsage?>(null) }
    when (val current = state) {
        Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBanner(current.message, Modifier.padding(16.dp))
        is Loadable.Ready -> LazyColumn(Modifier.fillMaxSize().testTag("payees-screen"), contentPadding = PaddingValues(14.dp)) {
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp)) } }
            learning?.let { (enabled, _) ->
                item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Learn categories", style = MaterialTheme.typography.bodyLarge)
                            Text("Create payee rules from how you categorize, as Actual does", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(checked = enabled, onCheckedChange = viewModel::setLearning, modifier = Modifier.testTag("learn-categories"))
                    }
                    HorizontalDivider()
                }
            }
            items(current.value, key = { it.id }) { payee ->
                Row(Modifier.fillMaxWidth().clickable { selected = payee }.padding(vertical = 12.dp).testTag("payee-${payee.id}")) {
                    Text(payee.name, Modifier.weight(1f))
                    Text("${payee.transactionCount}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
    selected?.let { payee ->
        var name by remember(payee.id) { mutableStateOf(payee.name) }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(payee.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("payee-name"))
                    OutlinedButton(onClick = { merging = payee; selected = null }, modifier = Modifier.fillMaxWidth().testTag("payee-merge")) { Text("Merge into another payee…") }
                    learning?.let { (_, off) ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Learn this payee's category", Modifier.weight(1f))
                            androidx.compose.material3.Switch(
                                checked = payee.id !in off,
                                onCheckedChange = { viewModel.setPayeeLearns(payee.id, it); selected = null },
                                modifier = Modifier.testTag("payee-learns"),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.rename(payee.id, name); selected = null }, enabled = name.isNotBlank()) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } },
        )
    }
    merging?.let { source ->
        val options = ((state as? Loadable.Ready)?.value ?: emptyList()).filter { it.id != source.id }.map { PickerOption(it.id, it.name, "${it.transactionCount} transactions") }
        PickerDialog(
            title = "Merge ${source.name} into",
            options = options,
            searchable = true,
            onPick = { option ->
                option.id?.let { viewModel.merge(source.id, it) }
                merging = null
            },
            onDismiss = { merging = null },
        )
    }
}
