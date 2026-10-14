package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sporez.actualist.android.ui.Loadable
import com.sporez.actualist.android.ui.MessageState
import com.sporez.actualist.android.ui.components.ErrorBanner
import com.sporez.actualist.android.ui.components.LoadingBox
import com.sporez.actualist.android.ui.components.MessageBox
import com.sporez.actualist.android.ui.components.MoneyText
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.android.ui.components.money
import com.sporez.actualist.android.ui.reloading
import com.sporez.actualist.core.schedules.ScheduleListItem
import com.sporez.actualist.core.schedules.ScheduleStatus
import com.sporez.actualist.core.schedules.Schedules
import com.sporez.actualist.core.store.BudgetSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

class SchedulesViewModel(private val session: BudgetSession) : ViewModel() {
    val messages = MessageState()
    private fun today() = LocalDate.now().toString()
    val schedules = reloading(session, MutableStateFlow(Unit)) { Schedules(database, clock).list(today()) }

    fun post(item: ScheduleListItem) = write("Posted ${item.name}") { Schedules(database, clock).postNow(item.id, today()) }
    fun skip(item: ScheduleListItem) = write("Skipped to the next date") { Schedules(database, clock).skipNextDate(item.id) }
    fun complete(item: ScheduleListItem) = write("Completed") { Schedules(database, clock).complete(item.id) }
    suspend fun choices(): Pair<List<com.sporez.actualist.android.ui.components.PickerOption>, List<com.sporez.actualist.android.ui.components.PickerOption>> = session.read {
        ledger.accounts(includeClosed = false).map { com.sporez.actualist.android.ui.components.PickerOption(it.id, it.name) } to
            ledger.payees().filter { !it.isTransfer }.map { com.sporez.actualist.android.ui.components.PickerOption(it.id, it.name) }
    }

    /**
     * Creates a schedule through the ported schedule writer: [frequency] null is a one-time
     * schedule; [approximate] matches amounts within Actual's 7.5% "isapprox" window.
     */
    fun create(name: String, accountId: String, payeeId: String?, amount: Long, approximate: Boolean, start: LocalDate, frequency: com.sporez.actualist.core.rules.ScheduleFrequency?, posts: Boolean) =
        write("Added ${name.ifBlank { "schedule" }}") {
            val operation = if (approximate) "isapprox" else "is"
            val rule = if (frequency == null) {
                com.sporez.actualist.core.schedules.ScheduleDateRule.OneTime(start.toString(), operation)
            } else {
                com.sporez.actualist.core.schedules.ScheduleDateRule.Recurring(com.sporez.actualist.core.rules.ScheduleRecurrence(start.toString(), frequency), operation)
            }
            Schedules(database, clock).create(
                com.sporez.actualist.core.schedules.ScheduleCreateCommand(
                    identity = com.sporez.actualist.core.schedules.ScheduleCreateIdentity.make(),
                    name = name.trim().ifEmpty { null },
                    definition = com.sporez.actualist.core.schedules.ScheduleDefinitionDraft(
                        accountId = accountId,
                        payeeMappingId = payeeId,
                        amount = if (approximate) com.sporez.actualist.core.schedules.ScheduleAmountDraft.Approximate(amount)
                        else com.sporez.actualist.core.schedules.ScheduleAmountDraft.Exact(amount),
                        dateRule = rule,
                    ),
                    postsTransaction = posts,
                    customUpcomingLength = null,
                    asOfDayId = today(),
                ),
            )
        }

    /** The schedule's current date rule, for the editor. */
    suspend fun dateRule(item: ScheduleListItem): com.sporez.actualist.core.schedules.ScheduleDateRule? =
        session.read { Schedules(database, clock).editor(item.id, today())?.dateRule }

    /** Edits name, amount, auto-posting and (for simple rules) the date rule; only changed fields are sent. */
    fun update(
        item: ScheduleListItem,
        name: String,
        amount: Long?,
        approximate: Boolean,
        posts: Boolean,
        dateRule: com.sporez.actualist.core.schedules.ScheduleDateRule? = null,
    ) =
        write("Updated ${name.ifBlank { item.name }}") {
            val trimmed = name.trim()
            val amountChanged = amount != null && (amount != item.amountMinorUnits || approximate != item.isApproximate)
            val fields = com.sporez.actualist.core.schedules.ScheduleEditFields(
                name = if (trimmed != item.name) com.sporez.actualist.core.schedules.ScheduleOptionalChange.Assign(trimmed.ifEmpty { null })
                else com.sporez.actualist.core.schedules.ScheduleOptionalChange.Unchanged,
                amount = if (amountChanged) {
                    com.sporez.actualist.core.schedules.ScheduleOptionalChange.Assign(
                        if (approximate) com.sporez.actualist.core.schedules.ScheduleAmountDraft.Approximate(amount!!)
                        else com.sporez.actualist.core.schedules.ScheduleAmountDraft.Exact(amount!!),
                    )
                } else {
                    com.sporez.actualist.core.schedules.ScheduleOptionalChange.Unchanged
                },
                postsTransaction = posts.takeIf { it != item.postsTransaction },
                dateRule = dateRule?.let { com.sporez.actualist.core.schedules.ScheduleOptionalChange.Assign(it) }
                    ?: com.sporez.actualist.core.schedules.ScheduleOptionalChange.Unchanged,
            )
            if (!fields.isEmpty) Schedules(database, clock).update(item.id, fields, today())
        }

    fun delete(item: ScheduleListItem) = write("Deleted ${item.name}") { Schedules(database, clock).delete(item.id) }

    private fun write(done: String, block: BudgetSession.() -> Any?) {
        viewModelScope.launch {
            try {
                session.write { block() }
                messages.show(done)
            } catch (error: Exception) {
                messages.show(error.message ?: "The schedule could not be changed.")
            }
        }
    }
}

@Composable
fun SchedulesScreen(viewModel: SchedulesViewModel) {
    val state by viewModel.schedules.collectAsState()
    val message by viewModel.messages.message.collectAsState()
    var adding by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    when (val current = state) {
        Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBanner(current.message, Modifier.padding(16.dp))
        is Loadable.Ready -> LazyColumn(Modifier.fillMaxSize().testTag("schedules-screen"), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                androidx.compose.material3.OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().testTag("schedule-add")) { Text("Add schedule") }
            }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("schedules-message")) } }
            if (current.value.isEmpty()) item { Text("No schedules yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(current.value, key = { it.id }) { item -> ScheduleCard(item, viewModel) }
        }
    }
    if (adding) AddScheduleDialog(viewModel) { adding = false }
}

@Composable
private fun AddScheduleDialog(viewModel: SchedulesViewModel, onDismiss: () -> Unit) {
    var name by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var amountText by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var outflow by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    var approximate by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    var posts by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var startText by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(LocalDate.now().plusDays(1).toString()) }
    var frequency by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("MONTHLY") }
    var accountId by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var payeeId by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var choices by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<List<com.sporez.actualist.android.ui.components.PickerOption>, List<com.sporez.actualist.android.ui.components.PickerOption>>?>(null) }
    var picking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { choices = viewModel.choices() }
    val magnitude = com.sporez.actualist.core.money.Money.parse(amountText)
    val start = runCatching { LocalDate.parse(startText.trim()) }.getOrNull()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add schedule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("schedule-dialog")) {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("schedule-name"))
                TextButton(onClick = { picking = "account" }, modifier = Modifier.testTag("schedule-account")) {
                    Text(choices?.first?.firstOrNull { it.id == accountId }?.title ?: "Choose an account")
                }
                TextButton(onClick = { picking = "payee" }, modifier = Modifier.testTag("schedule-payee")) {
                    Text(choices?.second?.firstOrNull { it.id == payeeId }?.title ?: "Choose a payee (optional)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.FilterChip(selected = outflow, onClick = { outflow = true }, label = { Text("Outflow") })
                    androidx.compose.material3.FilterChip(selected = !outflow, onClick = { outflow = false }, label = { Text("Inflow") }, modifier = Modifier.padding(start = 6.dp))
                }
                com.sporez.actualist.android.ui.components.AmountField(amountText, { amountText = it }, label = "Amount", tag = "schedule-amount")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = approximate, onCheckedChange = { approximate = it })
                    Text("Approximate amount")
                }
                androidx.compose.material3.OutlinedTextField(startText, { startText = it }, label = { Text("First date (YYYY-MM-DD)") }, singleLine = true, isError = start == null, modifier = Modifier.fillMaxWidth().testTag("schedule-start"))
                Row {
                    for ((key, label) in listOf("ONCE" to "Once", "WEEKLY" to "Weekly", "MONTHLY" to "Monthly", "YEARLY" to "Yearly")) {
                        androidx.compose.material3.FilterChip(selected = frequency == key, onClick = { frequency = key }, label = { Text(label) }, modifier = Modifier.padding(end = 4.dp).testTag("schedule-frequency-$key"))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = posts, onCheckedChange = { posts = it })
                    Text("Post the transaction automatically")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = accountId != null && magnitude != null && magnitude != 0L && start != null,
                onClick = {
                    val signed = kotlin.math.abs(magnitude!!).let { if (outflow) -it else it }
                    val freq = if (frequency == "ONCE") null else com.sporez.actualist.core.rules.ScheduleFrequency.valueOf(frequency)
                    viewModel.create(name, accountId!!, payeeId, signed, approximate, start!!, freq, posts)
                    onDismiss()
                },
                modifier = Modifier.testTag("schedule-save"),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    when (picking) {
        "account" -> com.sporez.actualist.android.ui.components.PickerDialog(
            title = "Account",
            options = choices?.first.orEmpty(),
            onPick = { accountId = it.id; picking = null },
            onDismiss = { picking = null },
        )
        "payee" -> com.sporez.actualist.android.ui.components.PickerDialog(
            title = "Payee",
            options = listOf(com.sporez.actualist.android.ui.components.PickerOption(null, "No payee")) + choices?.second.orEmpty(),
            searchable = true,
            onPick = { payeeId = it.id; picking = null },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun ScheduleCard(item: ScheduleListItem, viewModel: SchedulesViewModel) {
    var editing by androidx.compose.runtime.saveable.rememberSaveable(item.id) { androidx.compose.runtime.mutableStateOf(false) }
    if (editing) EditScheduleDialog(item, viewModel) { editing = false }
    SectionCard(Modifier.testTag("schedule-${item.id}")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                // Actual shows an approximate amount as "~$X" and only a between-amount as a range.
                when {
                    item.isApproximate && item.amountMinorUnits != null -> Row {
                        Text("~", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(item.amountMinorUnits!!, colored = true)
                    }
                    item.amountLower != null && item.amountUpper != null && item.amountLower != item.amountUpper ->
                        Text("${money(item.amountLower!!)} – ${money(item.amountUpper!!)}")
                    item.amountMinorUnits != null -> MoneyText(item.amountMinorUnits!!, colored = true)
                }
            }
            Text(
                listOfNotNull(item.payeeName, item.accountName).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("${item.recurrenceDescription} · next ${item.nextDate ?: "none"}", style = MaterialTheme.typography.bodySmall)
            Text(
                statusLabel(item.status),
                style = MaterialTheme.typography.labelLarge,
                color = when (item.status) {
                    ScheduleStatus.MISSED -> MaterialTheme.colorScheme.error
                    ScheduleStatus.DUE, ScheduleStatus.UPCOMING -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.testTag("schedule-status-${item.id}"),
            )
            Row {
                if (item.isSupported && !item.completed) TextButton(onClick = { editing = true }, modifier = Modifier.testTag("schedule-edit-${item.id}")) { Text("Edit") }
                if (item.canPost) TextButton(onClick = { viewModel.post(item) }, modifier = Modifier.testTag("schedule-post-${item.id}")) { Text("Post now") }
                if (item.canSkip) TextButton(onClick = { viewModel.skip(item) }, modifier = Modifier.testTag("schedule-skip-${item.id}")) { Text("Skip") }
                if (item.canComplete) TextButton(onClick = { viewModel.complete(item) }) { Text("Complete") }
                if (item.canDelete) TextButton(onClick = { viewModel.delete(item) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun statusLabel(status: ScheduleStatus) = when (status) {
    ScheduleStatus.COMPLETED -> "Completed"
    ScheduleStatus.PAID -> "Paid"
    ScheduleStatus.DUE -> "Due today"
    ScheduleStatus.UPCOMING -> "Upcoming"
    ScheduleStatus.MISSED -> "Missed"
    ScheduleStatus.SCHEDULED -> "Scheduled"
}


@Composable
private fun EditScheduleDialog(item: ScheduleListItem, viewModel: SchedulesViewModel, onDismiss: () -> Unit) {
    val isRange = item.amountLower != null && item.amountUpper != null && item.amountLower != item.amountUpper
    var name by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(item.name) }
    var amountText by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(item.amountMinorUnits?.let { com.sporez.actualist.core.money.Money.format(kotlin.math.abs(it), symbol = "", grouping = false) } ?: "")
    }
    var outflow by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf((item.amountMinorUnits ?: -1L) < 0) }
    var approximate by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(item.isApproximate) }
    var posts by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(item.postsTransaction) }
    val magnitude = com.sporez.actualist.core.money.Money.parse(amountText)
    var rule by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.sporez.actualist.core.schedules.ScheduleDateRule?>(null) }
    var startText by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var frequency by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(item.id) {
        val loaded = viewModel.dateRule(item)
        rule = loaded
        when (val current = loaded) {
            is com.sporez.actualist.core.schedules.ScheduleDateRule.OneTime -> { startText = current.dayId; frequency = "ONCE" }
            is com.sporez.actualist.core.schedules.ScheduleDateRule.Recurring -> { startText = current.recurrenceValue.startDayId; frequency = current.recurrenceValue.frequency.name }
            else -> {}
        }
    }
    // Only one-time and plain recurring rules are edited here; others keep their rule.
    val simpleRule = when (val current = rule) {
        is com.sporez.actualist.core.schedules.ScheduleDateRule.OneTime -> true
        is com.sporez.actualist.core.schedules.ScheduleDateRule.Recurring -> current.recurrenceValue.let {
            it.interval == 1L && it.patterns.isEmpty() && !it.skipWeekend && it.ending == com.sporez.actualist.core.rules.ScheduleEnding.Never &&
                it.frequency != com.sporez.actualist.core.rules.ScheduleFrequency.DAILY
        }
        else -> false
    }
    val start = runCatching { java.time.LocalDate.parse(startText.trim()) }.getOrNull()
    fun editedRule(): com.sporez.actualist.core.schedules.ScheduleDateRule? {
        val current = rule ?: return null
        if (!simpleRule || start == null) return null
        val operation = when (current) {
            is com.sporez.actualist.core.schedules.ScheduleDateRule.OneTime -> current.operation
            is com.sporez.actualist.core.schedules.ScheduleDateRule.Recurring -> current.operation
            else -> return null
        }
        val next = if (frequency == "ONCE") {
            com.sporez.actualist.core.schedules.ScheduleDateRule.OneTime(start.toString(), operation)
        } else {
            com.sporez.actualist.core.schedules.ScheduleDateRule.Recurring(
                com.sporez.actualist.core.rules.ScheduleRecurrence(start.toString(), com.sporez.actualist.core.rules.ScheduleFrequency.valueOf(frequency)),
                operation,
            )
        }
        return next.takeIf { it != current }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit schedule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("schedule-edit-dialog")) {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("schedule-edit-name"))
                if (isRange) {
                    Text("This schedule matches an amount range; edit the range in Actual.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.FilterChip(selected = outflow, onClick = { outflow = true }, label = { Text("Outflow") })
                        androidx.compose.material3.FilterChip(selected = !outflow, onClick = { outflow = false }, label = { Text("Inflow") }, modifier = Modifier.padding(start = 6.dp))
                    }
                    com.sporez.actualist.android.ui.components.AmountField(amountText, { amountText = it }, label = "Amount", tag = "schedule-edit-amount")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = approximate, onCheckedChange = { approximate = it })
                        Text("Approximate amount")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = posts, onCheckedChange = { posts = it })
                    Text("Post the transaction automatically")
                }
                if (simpleRule) {
                    androidx.compose.material3.OutlinedTextField(startText, { startText = it }, label = { Text("First date (YYYY-MM-DD)") }, singleLine = true, isError = start == null, modifier = Modifier.fillMaxWidth().testTag("schedule-edit-start"))
                    Row {
                        for ((key, label) in listOf("ONCE" to "Once", "WEEKLY" to "Weekly", "MONTHLY" to "Monthly", "YEARLY" to "Yearly")) {
                            androidx.compose.material3.FilterChip(selected = frequency == key, onClick = { frequency = key }, label = { Text(label) }, modifier = Modifier.padding(end = 4.dp).testTag("schedule-edit-frequency-$key"))
                        }
                    }
                } else {
                    Text("Repeats: ${item.recurrenceDescription} (edit this rule in Actual)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = (isRange || (magnitude != null && magnitude != 0L)) && (!simpleRule || start != null),
                onClick = {
                    val signed = if (isRange) null else kotlin.math.abs(magnitude!!).let { if (outflow) -it else it }
                    viewModel.update(item, name, signed, approximate, posts, editedRule())
                    onDismiss()
                },
                modifier = Modifier.testTag("schedule-edit-save"),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
