@file:OptIn(ExperimentalMaterial3Api::class)

package com.sporez.actualist.android.ui.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.Loadable
import com.sporez.actualist.android.ui.components.AmountField
import com.sporez.actualist.android.ui.components.BalancePill
import com.sporez.actualist.android.ui.components.ErrorBanner
import com.sporez.actualist.android.ui.components.LoadingBox
import com.sporez.actualist.android.ui.components.MoneyText
import com.sporez.actualist.android.ui.components.PickerDialog
import com.sporez.actualist.android.ui.components.PickerOption
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.android.ui.components.money
import com.sporez.actualist.android.ui.theme.LocalMoneyColors
import com.sporez.actualist.android.ui.theme.MoneyTextStyle
import com.sporez.actualist.core.budget.BudgetMonthCategory
import com.sporez.actualist.core.budget.BudgetMonthCategoryGroup
import com.sporez.actualist.core.budget.BudgetTable
import com.sporez.actualist.core.money.Money
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthFormat = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)

fun monthTitle(month: String): String = runCatching { YearMonth.parse(month).format(monthFormat) }.getOrDefault(month)

@Composable
fun BudgetScreen(viewModel: BudgetViewModel, onShowCategoryTransactions: (String, String) -> Unit) {
    val state by viewModel.state.collectAsState()
    val month by viewModel.month.collectAsState()
    val message by viewModel.messages.message.collectAsState()
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var showToBudget by remember { mutableStateOf(false) }
    var addingCategory by remember { mutableStateOf(false) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

    Column(Modifier.fillMaxSize().testTag("budget-screen")) {
        MonthHeader(month, onPrevious = viewModel::previousMonth, onNext = viewModel::nextMonth)
        when (val current = state) {
            Loadable.Loading -> LoadingBox()
            is Loadable.Failed -> ErrorBanner(current.message, Modifier.padding(16.dp))
            is Loadable.Ready -> {
                val screen = current.value
                LazyColumn(
                    Modifier.fillMaxSize().testTag("budget-list"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    message?.let { text ->
                        item(key = "message") {
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clickable { viewModel.messages.clear() }) {
                                Text(text, Modifier.padding(12.dp).testTag("budget-message"), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (screen.month.table == BudgetTable.ENVELOPE) {
                        item(key = "to-budget") { ToBudgetBanner(screen.month.toBudget, screen.month.forNextMonth) { showToBudget = true } }
                    } else {
                        item(key = "tracking") { TrackingSummaryCard(screen) }
                    }
                    screen.note?.let { note ->
                        item(key = "note") {
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                                Text(note, Modifier.padding(12.dp).testTag("month-note"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (screen.overspent.isNotEmpty()) {
                        item(key = "overspent") { OverspentRow(screen.overspent.size) { selectedCategory = screen.overspent.first().id } }
                    }
                    for (group in screen.month.categoryGroups.filter { !it.hidden }) {
                        val isCollapsed = collapsed[group.id] == true
                        item(key = "group-${group.id}") {
                            GroupHeader(group, isCollapsed) { collapsed[group.id] = !isCollapsed }
                        }
                        if (!isCollapsed) {
                            item(key = "card-${group.id}") {
                                SectionCard {
                                    val visible = group.categories.filter { !it.hidden }
                                    visible.forEachIndexed { index, category ->
                                        CategoryRow(category) { selectedCategory = category.id }
                                        if (index < visible.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                }
                            }
                        }
                    }
                    item(key = "add-category") {
                        TextButton(onClick = { addingCategory = true }, modifier = Modifier.testTag("add-category")) { Text("Add category") }
                    }
                }
                val category = selectedCategory?.let { id -> screen.month.category(id) }
                if (category != null) {
                    CategorySheet(
                        category = category,
                        screen = screen,
                        onDismiss = { selectedCategory = null },
                        viewModel = viewModel,
                        onShowTransactions = {
                            selectedCategory = null
                            onShowCategoryTransactions(category.id, category.name)
                        },
                    )
                }
                if (showToBudget) ToBudgetSheet(screen, viewModel) { showToBudget = false }
                if (addingCategory) AddCategoryDialog(screen, viewModel) { addingCategory = false }
            }
        }
    }
}

@Composable
private fun MonthHeader(month: String, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, modifier = Modifier.testTag("month-previous")) { Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous month") }
        Text(monthTitle(month), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).testTag("month-title"), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = onNext, modifier = Modifier.testTag("month-next")) { Icon(Icons.Filled.ChevronRight, contentDescription = "Next month") }
    }
}

@Composable
private fun ToBudgetBanner(toBudget: Long, held: Long, onClick: () -> Unit) {
    val colors = LocalMoneyColors.current
    val (background, foreground, label) = when {
        toBudget > 0 -> Triple(colors.positiveContainer, colors.onPositiveContainer, "To Budget")
        toBudget < 0 -> Triple(colors.negativeContainer, colors.onNegativeContainer, "Overbudgeted")
        else -> Triple(colors.neutralContainer, colors.onNeutralContainer, "To Budget")
    }
    Surface(shape = RoundedCornerShape(22.dp), color = background, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("to-budget-banner")) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(money(toBudget), color = foreground, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.testTag("to-budget-amount"))
            Spacer(Modifier.width(10.dp))
            Text(label, color = foreground, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            if (held != 0L) Text("Held ${money(held)}", color = foreground, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TrackingSummaryCard(screen: BudgetScreenState) {
    val tracking = screen.month.trackingSummary ?: return
    SectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Tracking budget", style = MaterialTheme.typography.titleMedium)
            SummaryLine("Budgeted income", tracking.budgetedIncome)
            SummaryLine("Received", tracking.receivedIncome)
            SummaryLine("Budgeted expenses", tracking.budgetedExpenses)
            SummaryLine("Spent", tracking.expenseActivity)
            SummaryLine("Planned savings", tracking.plannedSavings)
        }
    }
}

@Composable
private fun SummaryLine(label: String, amount: Long) {
    Row {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(amount)
    }
}

@Composable
private fun OverspentRow(count: Int, onCover: () -> Unit) {
    SectionCard(Modifier.clickable(onClick = onCover).testTag("overspent-row")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).background(LocalMoneyColors.current.negativeContainer, CircleShape), contentAlignment = Alignment.Center) {
                Text("$count", color = LocalMoneyColors.current.onNegativeContainer, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(12.dp))
            Text(if (count == 1) "Overspent category" else "Overspent categories", Modifier.weight(1f))
            Text("Cover", style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun GroupHeader(group: BudgetMonthCategoryGroup, collapsed: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(top = 6.dp, start = 4.dp, end = 4.dp).testTag("group-${group.id}"), verticalAlignment = Alignment.Bottom) {
        Icon(if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, contentDescription = if (collapsed) "Expand" else "Collapse", modifier = Modifier.size(20.dp))
        Text(group.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(horizontalAlignment = Alignment.End) {
            Text(if (group.isIncome) "Received" else "Assigned", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(money(if (group.isIncome) group.spent else group.budgeted), style = MoneyTextStyle)
        }
        if (!group.isIncome) {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(110.dp)) {
                Text("Available", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(money(group.balance), style = MoneyTextStyle)
            }
        }
    }
}

@Composable
private fun CategoryRow(category: BudgetMonthCategory, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp).testTag("category-${category.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(category.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        if (category.isIncome) {
            MoneyText(category.spent)
        } else {
            Text(money(category.budgeted), style = MoneyTextStyle, modifier = Modifier.padding(end = 10.dp))
            Box(Modifier.width(100.dp), contentAlignment = Alignment.CenterEnd) { BalancePill(category.balance) }
        }
    }
}

@Composable
private fun CategorySheet(
    category: BudgetMonthCategory,
    screen: BudgetScreenState,
    onDismiss: () -> Unit,
    viewModel: BudgetViewModel,
    onShowTransactions: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var assignText by rememberSaveable(category.id) { mutableStateOf(Money.format(category.budgeted, symbol = "", grouping = false)) }
    var moveText by rememberSaveable(category.id) { mutableStateOf("") }
    var picking by remember { mutableStateOf<String?>(null) }
    val envelope = screen.month.table == BudgetTable.ENVELOPE
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).testTag("category-sheet"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(category.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (!category.isIncome) BalancePill(category.balance)
            }
            Row {
                Text("Spent this month", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                MoneyText(category.spent)
            }
            if (!category.isIncome || !envelope) {
                AmountField(assignText, { assignText = it }, label = "Assigned in ${monthTitle(screen.month.month)}", tag = "assign-field")
                Button(
                    onClick = { Money.parse(assignText)?.let { viewModel.assign(category.id, it); onDismiss() } },
                    enabled = Money.parse(assignText) != null,
                    modifier = Modifier.fillMaxWidth().testTag("assign-save"),
                ) { Text("Save assignment") }
            }
            if (envelope && !category.isIncome) {
                if (category.balance < 0) {
                    OutlinedButton(onClick = { picking = "cover" }, modifier = Modifier.fillMaxWidth().testTag("cover-button")) {
                        Text("Cover ${money(-category.balance)} overspending")
                    }
                }
                AmountField(moveText, { moveText = it }, label = "Move amount", tag = "move-field")
                OutlinedButton(
                    onClick = { picking = "move" },
                    enabled = (Money.parse(moveText) ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth().testTag("move-button"),
                ) { Text("Move to another category…") }
            }
            if (!category.isIncome) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Roll over overspending", style = MaterialTheme.typography.bodyLarge)
                        Text("Negative balances carry into next month", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = category.carryover, onCheckedChange = { viewModel.setCarryover(category.id, it) }, modifier = Modifier.testTag("rollover-switch"))
                }
            }
            Row {
                TextButton(onClick = onShowTransactions, modifier = Modifier.testTag("category-transactions")) { Text("Show transactions") }
                if (!category.isIncome) TextButton(onClick = { viewModel.hideCategory(category.id); onDismiss() }, modifier = Modifier.testTag("hide-category")) { Text("Hide category") }
            }
        }
    }
    val expenseCategories = screen.month.categoryGroups.filter { !it.isIncome }.flatMap { group -> group.categories.map { it to group } }.filter { it.first.id != category.id }
    when (picking) {
        "move" -> PickerDialog(
            title = "Move to",
            options = listOf(PickerOption(null, "To Budget", money(screen.month.toBudget))) + expenseCategories.map { (c, g) -> PickerOption(c.id, c.name, "${g.name} · ${money(c.balance)}") },
            onPick = { option ->
                picking = null
                Money.parse(moveText)?.let { viewModel.move(category.id, option.id, it) }
                onDismiss()
            },
            onDismiss = { picking = null },
        )
        "cover" -> PickerDialog(
            title = "Cover from",
            options = listOf(PickerOption(null, "To Budget", money(screen.month.toBudget))) + expenseCategories.filter { it.first.balance > 0 }.map { (c, g) -> PickerOption(c.id, c.name, "${g.name} · ${money(c.balance)}") },
            onPick = { option ->
                picking = null
                viewModel.cover(category.id, option.id, -category.balance)
                onDismiss()
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun ToBudgetSheet(screen: BudgetScreenState, viewModel: BudgetViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var amountText by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    val month = screen.month
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).testTag("to-budget-sheet"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(monthTitle(month.month), style = MaterialTheme.typography.titleLarge)
            SummaryLine("Available funds", month.incomeAvailable)
            SummaryLine("Overspent last month", month.lastMonthOverspent)
            SummaryLine("Assigned", -month.totalBudgeted)
            SummaryLine("For next month", -month.forNextMonth)
            HorizontalDivider()
            SummaryLine("To Budget", month.toBudget)
            Spacer(Modifier.height(4.dp))
            AmountField(amountText, { amountText = it }, label = "Amount", tag = "to-budget-amount-field")
            val amount = Money.parse(amountText) ?: 0
            OutlinedButton(onClick = { picking = true }, enabled = amount > 0, modifier = Modifier.fillMaxWidth().testTag("assign-from-to-budget")) { Text("Assign to a category…") }
            OutlinedButton(onClick = { viewModel.hold(amount); onDismiss() }, enabled = amount > 0, modifier = Modifier.fillMaxWidth().testTag("hold-button")) { Text("Hold for next month") }
            if (month.forNextMonth != 0L) {
                TextButton(onClick = { viewModel.releaseHold(); onDismiss() }, modifier = Modifier.testTag("release-hold")) { Text("Release ${money(month.forNextMonth)} hold") }
            }
            HorizontalDivider()
            Text("Whole month", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(onClick = { viewModel.copyPreviousMonth(); onDismiss() }, modifier = Modifier.testTag("copy-previous-month")) { Text("Copy last month") }
                TextButton(onClick = { viewModel.setThreeMonthAverage(); onDismiss() }, modifier = Modifier.testTag("three-month-average")) { Text("3-month avg") }
                TextButton(onClick = { viewModel.setZero(); onDismiss() }, modifier = Modifier.testTag("set-zero")) { Text("Zero all") }
            }
            TextButton(onClick = { showTemplates = true }, modifier = Modifier.testTag("templates-open")) { Text("Apply budget templates…") }
        }
    }
    if (showTemplates) TemplatesDialog(viewModel) { showTemplates = false; onDismiss() }
    if (picking) {
        PickerDialog(
            title = "Assign to",
            options = month.categoryGroups.filter { !it.isIncome }.flatMap { g -> g.categories.map { PickerOption(it.id, it.name, "${g.name} · ${money(it.balance)}") } },
            onPick = { option ->
                picking = false
                Money.parse(amountText)?.let { viewModel.move(null, option.id, it) }
                onDismiss()
            },
            onDismiss = { picking = false },
        )
    }
}


@Composable
private fun AddCategoryDialog(screen: BudgetScreenState, viewModel: BudgetViewModel, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var groupId by rememberSaveable { mutableStateOf<String?>(screen.month.categoryGroups.firstOrNull { !it.isIncome }?.id) }
    var newGroup by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val groups = screen.month.categoryGroups.filter { !it.isIncome }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("new-category-name"))
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth().testTag("new-category-group")) {
                    Text(groups.firstOrNull { it.id == groupId }?.name ?: newGroup.ifBlank { "New group" })
                }
                if (groupId == null) {
                    androidx.compose.material3.OutlinedTextField(newGroup, { newGroup = it }, label = { Text("New group name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && (groupId != null || newGroup.isNotBlank()),
                onClick = { viewModel.addCategory(name, groupId, newGroup.ifBlank { null }); onDismiss() },
                modifier = Modifier.testTag("new-category-save"),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (picking) {
        PickerDialog(
            title = "Group",
            options = groups.map { PickerOption(it.id, it.name) } + PickerOption(null, "New group…"),
            onPick = { option ->
                groupId = option.id
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}


/** Preview of the budget's templates for this month, then apply (fill empty or overwrite). */
@Composable
private fun TemplatesDialog(viewModel: BudgetViewModel, onDismiss: () -> Unit) {
    var overwrite by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<com.sporez.actualist.core.templates.TemplatePreview?>(null) }
    var loaded by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(overwrite) {
        preview = viewModel.templatePreview(overwrite)
        loaded = true
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Budget templates") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("templates-dialog")) {
                val p = preview
                when {
                    !loaded -> Text("Reading templates…")
                    p == null -> Text("No category in this budget has a template. Templates are written in Actual (category notes or the template editor).")
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Overwrite existing amounts", Modifier.weight(1f))
                            Switch(checked = overwrite, onCheckedChange = { overwrite = it }, modifier = Modifier.testTag("templates-overwrite"))
                        }
                        p.errors.firstOrNull()?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        val changed = p.categories.filter { it.proposed != it.current }
                        if (changed.isEmpty()) Text("Nothing to change.", style = MaterialTheme.typography.bodySmall)
                        for (category in changed.take(12)) {
                            Row {
                                Text(category.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${money(category.current)} → ${money(category.proposed)}", style = MoneyTextStyle)
                            }
                        }
                        if (changed.size > 12) Text("…and ${changed.size - 12} more", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        SummaryLine("Assigned", p.assigned)
                        if (p.released != 0L) SummaryLine("Released", p.released)
                        SummaryLine(if (p.isTracking) "Saved after" else "To Budget after", p.leftover)
                    }
                }
            }
        },
        confirmButton = {
            val p = preview
            TextButton(
                enabled = p != null && p.isReady && p.categories.any { it.proposed != it.current },
                onClick = { viewModel.applyTemplates(overwrite); onDismiss() },
                modifier = Modifier.testTag("templates-apply"),
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
