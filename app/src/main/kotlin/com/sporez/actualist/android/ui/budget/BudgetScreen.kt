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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import com.sporez.actualist.core.templates.editor.TemplateDraftEntry as TE
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.MoreVert
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
import com.sporez.actualist.core.budget.Months
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
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var managingGroup by remember { mutableStateOf<String?>(null) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    var choosingMonth by remember { mutableStateOf(false) }
    var reviewingCover by remember { mutableStateOf(false) }
    var reviewingUncategorized by remember { mutableStateOf(false) }
    val appSettings = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.sporez.actualist.android.ActualistApplication).container.settings
    val display by appSettings.settings.collectAsState()
    val uncategorizedState by viewModel.uncategorized.collectAsState()
    val uncategorizedCount = (uncategorizedState as? Loadable.Ready)?.value?.size ?: 0
    // Actualist's Months Shown: Auto is three months (the shown one and two more); a fixed count of N shows N.
    val followingCount = if (display.monthsShown == 0) 2 else display.monthsShown - 1

    Column(Modifier.fillMaxSize().testTag("budget-screen")) {
        MonthHeader(month, onPrevious = viewModel::previousMonth, onNext = viewModel::nextMonth, onChoose = { choosingMonth = true }, onToday = viewModel::showToday)
        if (choosingMonth) {
            MonthChooserDialog(month, onPick = { picked -> viewModel.showMonth(picked); choosingMonth = false }, onDismiss = { choosingMonth = false })
        }
        when (val current = state) {
            Loadable.Loading -> LoadingBox()
            is Loadable.Failed -> ErrorBanner(current.message, Modifier.padding(16.dp))
            is Loadable.Ready -> {
                val screen = current.value
                // Wide windows (tablets, landscape) show the next months beside this one, as Actualist's iPad layout.
                androidx.compose.foundation.layout.BoxWithConstraints(
                    Modifier.fillMaxSize().monthSwipe(display.swipeBetweenMonths, onPrevious = viewModel::previousMonth, onNext = viewModel::nextMonth),
                ) {
                val wide = maxWidth >= 840.dp && followingCount > 0
                androidx.compose.runtime.LaunchedEffect(wide, followingCount) { viewModel.setFollowingCount(if (wide) followingCount else 0) }
                Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.weight(1.3f).fillMaxHeight().testTag("budget-list"),
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
                    if (display.showTotalAssigned) {
                        item(key = "assigned") {
                            val assigned = screen.month.categoryGroups.filter { !it.isIncome }.sumOf { it.budgeted }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                                Text("Assigned", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(money(assigned), style = MoneyTextStyle, modifier = Modifier.testTag("month-assigned"))
                            }
                        }
                    }
                    if (screen.overspent.isNotEmpty()) {
                        item(key = "overspent") {
                            OverspentRow(screen.overspent.size) {
                                // Envelope budgets cover from To Budget or another category; tracking budgets just open the category.
                                if (screen.month.table == BudgetTable.ENVELOPE) reviewingCover = true else selectedCategory = screen.overspent.first().id
                            }
                        }
                    }
                    if (uncategorizedCount > 0) {
                        item(key = "uncategorized") { UncategorizedRow(uncategorizedCount) { reviewingUncategorized = true } }
                    }
                    for (group in screen.month.categoryGroups.filter { showHidden || !it.hidden }) {
                        val isCollapsed = collapsed[group.id] == true
                        item(key = "group-${group.id}") {
                            GroupHeader(group, isCollapsed, onManage = { managingGroup = group.id }) { collapsed[group.id] = !isCollapsed }
                        }
                        if (!isCollapsed) {
                            item(key = "card-${group.id}") {
                                SectionCard {
                                    val visible = group.categories.filter { showHidden || !it.hidden }
                                    visible.forEachIndexed { index, category ->
                                        CategoryRow(category, showCarryover = !display.hideCarryoverArrows) { selectedCategory = category.id }
                                        if (index < visible.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                }
                            }
                        }
                    }
                    item(key = "show-hidden") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Show hidden categories", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Switch(checked = showHidden, onCheckedChange = { showHidden = it }, modifier = Modifier.testTag("show-hidden-switch"))
                        }
                    }
                    item(key = "add-category") {
                        TextButton(onClick = { addingCategory = true }, modifier = Modifier.testTag("add-category")) { Text("Add category") }
                    }
                }
                if (wide) {
                    FollowingMonths(screen, viewModel, collapsed, Modifier.weight(maxOf(0.6f, followingCount / 2f)).fillMaxHeight()) { month, categoryId ->
                        // Editing a later month: switch to it and open the category there.
                        viewModel.showMonth(month)
                        selectedCategory = categoryId
                    }
                }
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
                if (reviewingCover) CoverReviewSheet(screen, viewModel) { reviewingCover = false }
                if (reviewingUncategorized) UncategorizedReviewSheet(viewModel) { reviewingUncategorized = false }
                if (addingCategory) AddCategoryDialog(screen, viewModel) { addingCategory = false }
                managingGroup?.let { id ->
                    val group = screen.month.categoryGroups.firstOrNull { it.id == id }
                    if (group == null) managingGroup = null else GroupManageDialog(group, screen, viewModel) { managingGroup = null }
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(month: String, onPrevious: () -> Unit, onNext: () -> Unit, onChoose: () -> Unit, onToday: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, modifier = Modifier.testTag("month-previous")) { Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous month") }
        Text(
            monthTitle(month),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).clickable(onClick = onChoose).padding(vertical = 8.dp).testTag("month-title"),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (month != Months.current()) TextButton(onClick = onToday, modifier = Modifier.testTag("month-today")) { Text("Today") }
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
private fun GroupHeader(group: BudgetMonthCategoryGroup, collapsed: Boolean, onManage: () -> Unit, onToggle: () -> Unit) {
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
        IconButton(onClick = onManage, modifier = Modifier.size(32.dp).testTag("group-manage-${group.id}")) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Manage ${group.name}", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CategoryRow(category: BudgetMonthCategory, showCarryover: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp).testTag("category-${category.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(category.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        if (category.hidden) Text("Hidden", Modifier.padding(end = 10.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (category.isIncome) {
            MoneyText(category.spent)
        } else {
            Text(money(category.budgeted), style = MoneyTextStyle, modifier = Modifier.padding(end = 10.dp))
            Box(Modifier.width(100.dp), contentAlignment = Alignment.CenterEnd) { BalancePill(category.balance, carryover = showCarryover && category.carryover) }
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
    var managing by remember { mutableStateOf(false) }
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
            CategoryNoteField(category.id, viewModel)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = onShowTransactions, modifier = Modifier.testTag("category-transactions")) { Text("Show transactions") }
                if (!category.isIncome) TextButton(onClick = { picking = "template" }, modifier = Modifier.testTag("category-template")) { Text("Template") }
                if (category.hidden) {
                    TextButton(onClick = { viewModel.unhideCategory(category.id); onDismiss() }, modifier = Modifier.testTag("unhide-category")) { Text("Unhide") }
                } else if (!category.isIncome) {
                    TextButton(onClick = { viewModel.hideCategory(category.id); onDismiss() }, modifier = Modifier.testTag("hide-category")) { Text("Hide category") }
                }
                TextButton(onClick = { managing = true }, modifier = Modifier.testTag("category-manage-button")) { Text("Manage") }
            }
        }
    }
    val expenseCategories = screen.month.categoryGroups.filter { !it.isIncome }.flatMap { group -> group.categories.map { it to group } }.filter { it.first.id != category.id }
    if (managing) CategoryManageDialog(category, screen, viewModel) { managing = false; onDismiss() }
    if (picking == "template") {
        TemplateEditorDialog(category, viewModel) { saved ->
            picking = null
            if (saved) onDismiss()
        }
    }
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


/** One entry of a template as the dialog edits it; "upto" stands for a monthly limit plus a refill. */
private data class TemplateForm(
    val kind: String = "monthly",
    val amountText: String = "",
    val monthText: String = java.time.YearMonth.now().plusMonths(6).toString(),
    val countText: String = "3",
    val scheduleId: String? = null,
    val priority: Int = TE.DEFAULT_PRIORITY,
)

private object TemplateForms {
    private fun money(value: Double) = Money.format(Math.round(value * 100), symbol = "", grouping = false)

    /** Forms for a stored template, or null when it uses something these forms cannot show. */
    fun from(entries: List<TE>, schedules: List<Pair<String, String>>): List<TemplateForm>? {
        val forms = mutableListOf<TemplateForm>()
        val limits = entries.filterIsInstance<TE.BalanceLimit>()
        val refills = entries.filterIsInstance<TE.Refill>()
        if (limits.size > 1 || refills.size > 1 || limits.size != refills.size) return null
        limits.singleOrNull()?.let { limit ->
            if (limit.hold || limit.period != com.sporez.actualist.core.templates.editor.TemplateLimitPeriod.MONTHLY || limit.start != null) return null
            forms += TemplateForm(kind = "upto", amountText = money(limit.amount), priority = refills.single().priority)
        }
        for (entry in entries) {
            forms += when (entry) {
                is TE.BalanceLimit, is TE.Refill -> continue
                is TE.MonthlyFixed -> if (entry.cadence == com.sporez.actualist.core.templates.editor.TemplateCadence.MONTH && entry.interval == 1) {
                    TemplateForm("monthly", amountText = money(entry.amount), priority = entry.priority)
                } else return null
                is TE.DateTarget -> if (entry.repeatInterval == null && !entry.isSpend) TemplateForm("by", amountText = money(entry.amount), monthText = entry.month, priority = entry.priority) else return null
                is TE.Average -> if (entry.adjustment == null) TemplateForm("average", countText = entry.numMonths.toString(), priority = entry.priority) else return null
                is TE.Copy -> if (entry.legacyLimit == null) TemplateForm("copy", countText = entry.lookBack.toString(), priority = entry.priority) else return null
                is TE.Goal -> TemplateForm("goal", amountText = money(entry.amount))
                is TE.Percentage -> if (entry.sourceCategory == "all income" && !entry.previous) {
                    TemplateForm("percent", countText = entry.percent.let { if (it == Math.rint(it)) it.toLong().toString() else it.toString() }, priority = entry.priority)
                } else return null
                is TE.Schedule -> if (entry.adjustment == null && !entry.full) {
                    TemplateForm("schedule", scheduleId = entry.scheduleId ?: schedules.firstOrNull { it.second == entry.name }?.first, priority = entry.priority)
                } else return null
                else -> return null
            }
        }
        return forms
    }

    /** The entries a form stands for, or null when it is incomplete. */
    fun entries(form: TemplateForm, schedules: List<Pair<String, String>>): List<TE>? {
        val amount = Money.parse(form.amountText)?.takeIf { it > 0 }?.let { it / 100.0 }
        val count = form.countText.trim().toIntOrNull()?.takeIf { it in 1..12 }
        return when (form.kind) {
            "monthly" -> amount?.let { listOf(TE.monthlyFixed(amount = it, priority = form.priority)) }
            "upto" -> amount?.let { listOf(TE.balanceLimit(amount = it), TE.refill(priority = form.priority)) }
            "by" -> {
                val month = runCatching { java.time.YearMonth.parse(form.monthText.trim()) }.getOrNull()
                if (amount != null && month != null) {
                    listOf(TE.dateTarget(amount = amount, month = month.toString(), priority = form.priority, repeatInterval = null, annual = false, isSpend = false))
                } else null
            }
            "schedule" -> schedules.firstOrNull { it.first == form.scheduleId }?.let { (id, name) -> listOf(TE.schedule(name = name, scheduleId = id, priority = form.priority)) }
            "percent" -> form.countText.trim().toDoubleOrNull()?.takeIf { it > 0 && it <= 100 }?.let {
                listOf(TE.percentage(percent = it, sourceCategory = "all income", priority = form.priority))
            }
            "average" -> count?.let { listOf(TE.average(numMonths = it, priority = form.priority)) }
            "copy" -> count?.let { listOf(TE.copyPrevious(lookBack = it, priority = form.priority)) }
            "goal" -> amount?.let { listOf(TE.goal(amount = it)) }
            else -> null
        }
    }

    val kinds = listOf(
        "monthly" to "Monthly", "upto" to "Up to", "by" to "By date", "schedule" to "Schedule",
        "percent" to "% of income", "average" to "Average", "copy" to "Copy", "goal" to "Goal",
    )
}

/**
 * The category's template, edited through the ported template editor model as a list of entries
 * (Actual runs them in priority order): monthly amount, up to a cap, saving by a date, a schedule's
 * amount, a share of income, an average or copy of earlier months, or a balance goal. A template
 * using anything else is shown read-only and can be removed; the editor's validation (Actualist's)
 * refuses whatever Actual would reject.
 */
@Composable
private fun TemplateEditorDialog(category: BudgetMonthCategory, viewModel: BudgetViewModel, onDismiss: (saved: Boolean) -> Unit) {
    var draft by remember { mutableStateOf<com.sporez.actualist.core.templates.editor.TemplateEditorDraft?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var schedules by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var forms by remember { mutableStateOf<List<TemplateForm>?>(null) }
    androidx.compose.runtime.LaunchedEffect(category.id) {
        draft = runCatching { viewModel.templateDraft(category.id) }.getOrNull()
        schedules = runCatching { viewModel.scheduleChoices() }.getOrDefault(emptyList())
        forms = draft?.let { current -> TemplateForms.from(current.entries, schedules)?.ifEmpty { listOf(TemplateForm()) } }
        loaded = true
    }
    val current = draft
    val editing = forms
    fun update(index: Int, form: TemplateForm) {
        forms = editing?.toMutableList()?.also { it[index] = form }
    }
    val planned = editing?.map { TemplateForms.entries(it, schedules) }
    val next = if (planned != null && planned.all { it != null }) planned.flatMap { it!! } else null
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { onDismiss(false) },
        title = { Text("Template for ${category.name}") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).testTag("template-editor"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    !loaded -> Text("Reading the template…")
                    current == null -> Text("This template is managed in the category's note or uses options this app cannot change. Edit it in Actual.")
                    editing == null -> Text("${current.entries.size} template entries this app shows read-only. Edit them in Actual, or remove the template here.")
                    else -> {
                        editing.forEachIndexed { index, form ->
                            TemplateFormCard(index, form, schedules, removable = editing.size > 1, onChange = { update(index, it) }) {
                                forms = editing.filterIndexed { i, _ -> i != index }
                            }
                        }
                        TextButton(onClick = { forms = editing + TemplateForm(kind = "goal") }, modifier = Modifier.testTag("template-add-entry")) { Text("Add another entry") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = next != null,
                onClick = {
                    viewModel.saveTemplate(category.id, com.sporez.actualist.core.templates.editor.TemplateEditorDraft(next!!))
                    onDismiss(true)
                },
                modifier = Modifier.testTag("template-save"),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (current != null && current.entries.isNotEmpty()) {
                    TextButton(
                        onClick = { viewModel.saveTemplate(category.id, com.sporez.actualist.core.templates.editor.TemplateEditorDraft(emptyList())); onDismiss(true) },
                        modifier = Modifier.testTag("template-remove"),
                    ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = { onDismiss(false) }) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun TemplateFormCard(
    index: Int,
    form: TemplateForm,
    schedules: List<Pair<String, String>>,
    removable: Boolean,
    onChange: (TemplateForm) -> Unit,
    onRemove: () -> Unit,
) {
    // Field tags keep their single-entry names for the first entry, so existing tests still apply.
    fun tag(name: String) = if (index == 0) name else "$name-$index"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (index > 0) androidx.compose.material3.HorizontalDivider()
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(TemplateForms.kinds.size) { i ->
                val (key, label) = TemplateForms.kinds[i]
                androidx.compose.material3.FilterChip(
                    selected = form.kind == key,
                    onClick = { onChange(form.copy(kind = key)) },
                    label = { Text(label) },
                    modifier = Modifier.testTag(tag("template-kind-$key")),
                )
            }
        }
        when (form.kind) {
            "monthly" -> AmountField(form.amountText, { onChange(form.copy(amountText = it)) }, label = "Monthly amount", tag = tag("template-amount"))
            "upto" -> {
                Text("Refill the category up to this balance each month.", style = MaterialTheme.typography.bodySmall)
                AmountField(form.amountText, { onChange(form.copy(amountText = it)) }, label = "Up to", tag = tag("template-amount"))
            }
            "by" -> {
                Text("Save toward a total by a month; the monthly amount is worked out for you.", style = MaterialTheme.typography.bodySmall)
                AmountField(form.amountText, { onChange(form.copy(amountText = it)) }, label = "Total", tag = tag("template-amount"))
                val valid = runCatching { java.time.YearMonth.parse(form.monthText.trim()) }.isSuccess
                androidx.compose.material3.OutlinedTextField(form.monthText, { onChange(form.copy(monthText = it)) }, label = { Text("By month (YYYY-MM)") }, singleLine = true, isError = !valid, modifier = Modifier.fillMaxWidth().testTag(tag("template-month")))
            }
            "schedule" -> {
                Text("Budget what a schedule needs before its next date.", style = MaterialTheme.typography.bodySmall)
                if (schedules.isEmpty()) Text("No active schedules yet.", style = MaterialTheme.typography.bodySmall)
                for ((id, name) in schedules) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(form.copy(scheduleId = id)) }.testTag(tag("template-schedule-$id"))) {
                        androidx.compose.material3.RadioButton(selected = form.scheduleId == id, onClick = { onChange(form.copy(scheduleId = id)) })
                        Text(name)
                    }
                }
            }
            "percent" -> {
                Text("Budget a share of this month's income.", style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.OutlinedTextField(form.countText, { value -> onChange(form.copy(countText = value.filter { it.isDigit() || it == '.' })) }, label = { Text("Percent of all income") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(tag("template-percent")))
            }
            "average", "copy" -> {
                Text(if (form.kind == "average") "Budget the average spent over the last months." else "Budget what was budgeted that many months ago.", style = MaterialTheme.typography.bodySmall)
                val count = form.countText.trim().toIntOrNull()
                androidx.compose.material3.OutlinedTextField(form.countText, { onChange(form.copy(countText = it.filter(Char::isDigit))) }, label = { Text("Months (1-12)") }, singleLine = true, isError = count == null || count !in 1..12, modifier = Modifier.fillMaxWidth().testTag(tag("template-months")))
            }
            "goal" -> {
                Text("A balance goal shown on the category; it does not assign money by itself.", style = MaterialTheme.typography.bodySmall)
                AmountField(form.amountText, { onChange(form.copy(amountText = it)) }, label = "Goal balance", tag = tag("template-amount"))
            }
        }
        if (removable) TextButton(onClick = onRemove, modifier = Modifier.testTag(tag("template-remove-entry"))) { Text("Remove this entry") }
    }
}

/** The category's note text (template directives in the same note are kept as they are). */
@Composable
private fun CategoryNoteField(categoryId: String, viewModel: BudgetViewModel) {
    var original by remember(categoryId) { mutableStateOf<String?>(null) }
    var text by remember(categoryId) { mutableStateOf("") }
    var loaded by remember(categoryId) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(categoryId) {
        original = runCatching { viewModel.categoryNote(categoryId) }.getOrNull()
        text = original.orEmpty()
        loaded = true
    }
    if (!loaded) return
    androidx.compose.material3.OutlinedTextField(text, { text = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth().testTag("category-notes"))
    if (text.trim() != original.orEmpty()) {
        TextButton(onClick = { viewModel.saveCategoryNote(categoryId, text); original = text.trim() }, modifier = Modifier.testTag("category-notes-save")) { Text("Save notes") }
    }
}


/** Read-only columns for the next two months: each category's budgeted amount and balance. */
@Composable
private fun FollowingMonths(
    screen: BudgetScreenState,
    viewModel: BudgetViewModel,
    collapsed: Map<String, Boolean>,
    modifier: Modifier,
    onEdit: (month: String, categoryId: String) -> Unit,
) {
    val state by viewModel.following.collectAsState()
    val months = (state as? Loadable.Ready)?.value ?: return
    var assigning by remember { mutableStateOf<Pair<String, String>?>(null) }
    LazyColumn(
        modifier.testTag("budget-following"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "months") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Next months", Modifier.weight(1.2f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (month in months) {
                    TextButton(onClick = { viewModel.showMonth(month.month) }, modifier = Modifier.weight(1f).testTag("following-${month.month}")) {
                        Text(monthTitle(month.month), maxLines = 1)
                    }
                }
            }
        }
        for (group in screen.month.categoryGroups.filter { !it.hidden }) {
            item(key = "group-${group.id}") {
                Text(group.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            }
            if (collapsed[group.id] != true) {
                item(key = "card-${group.id}") {
                    SectionCard {
                        for (category in group.categories.filter { !it.hidden }) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(category.name, Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                for (month in months) {
                                    val value = month.category(category.id)
                                    // As Actualist's grid: the assigned amount edits in place, the balance opens the category.
                                    val assignable = !category.isIncome || month.table != BudgetTable.ENVELOPE
                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                        Text(
                                            money(value?.budgeted ?: 0L),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .clickable(enabled = assignable) { assigning = month.month to category.id }
                                                .testTag("following-assign-${month.month}-${category.id}"),
                                        )
                                        Box(Modifier.clickable { onEdit(month.month, category.id) }.testTag("following-${month.month}-${category.id}")) {
                                            MoneyText(value?.balance ?: 0L, colored = true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    assigning?.let { (month, categoryId) ->
        val category = months.firstOrNull { it.month == month }?.category(categoryId)
        if (category == null) assigning = null else AssignInMonthDialog(month, category, viewModel) { assigning = null }
    }
}

@Composable
private fun AssignInMonthDialog(month: String, category: BudgetMonthCategory, viewModel: BudgetViewModel, onDismiss: () -> Unit) {
    var text by rememberSaveable(month, category.id) { mutableStateOf(Money.format(category.budgeted, symbol = "", grouping = false)) }
    val amount = Money.parse(text)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(category.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("following-assign-dialog")) {
                Text(monthTitle(month), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AmountField(text, { text = it }, label = "Assigned", tag = "following-assign-field")
            }
        },
        confirmButton = {
            TextButton(
                onClick = { amount?.let { viewModel.assignIn(month, category.id, it) }; onDismiss() },
                enabled = amount != null,
                modifier = Modifier.testTag("following-assign-save"),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
