@file:OptIn(ExperimentalMaterial3Api::class)

package com.sporez.actualist.android.ui.budget

import com.sporez.actualist.android.ui.components.Dates
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import com.sporez.actualist.android.ui.components.LocalSampleValues
import com.sporez.actualist.android.ui.components.LocalValuesProjected
import com.sporez.actualist.android.ui.components.NotesHiddenMessage
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
import com.sporez.actualist.core.privacy.SampleValues
import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.money.MoneyExpression
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Actualist's defaults: hidden groups and the envelope Income group start collapsed; the rest start expanded. */
private fun startsCollapsed(group: BudgetMonthCategoryGroup, table: BudgetTable): Boolean =
    group.hidden || (group.isIncome && table == BudgetTable.ENVELOPE)

private fun groupToggleKey(group: BudgetMonthCategoryGroup, table: BudgetTable): String =
    if (startsCollapsed(group, table)) "+${group.id}" else group.id

private fun isGroupCollapsed(group: BudgetMonthCategoryGroup, table: BudgetTable, toggled: Map<String, Boolean>): Boolean =
    startsCollapsed(group, table) != (toggled[groupToggleKey(group, table)] == true)

private val monthFormat: DateTimeFormatter get() = Dates.monthYear()

fun monthTitle(month: String): String = runCatching { YearMonth.parse(month).format(monthFormat) }.getOrDefault(month)

@Composable
fun BudgetScreen(
    viewModel: BudgetViewModel,
    onShowCategoryTransactions: (String, String) -> Unit,
    onOpenHistory: () -> Unit = {},
    onOpenSchedules: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** When set, "Show transactions" opens the category's month screen (category id, name, shown month id) instead of [onShowCategoryTransactions]. */
    onShowCategoryMonth: ((String, String, String) -> Unit)? = null,
    /** Pull-to-refresh: runs the budget sync while the indicator shows. */
    onRefresh: suspend () -> Unit = {},
    /** The floating add-transaction button (Actualist's bottom "Transaction" button). */
    onAddTransaction: () -> Unit = {},
) {
    // The month shown is projected to sample values as a whole (totals add up), so money() must not hash it again.
    androidx.compose.runtime.CompositionLocalProvider(LocalValuesProjected provides LocalSampleValues.current) {
        Box(Modifier.fillMaxSize()) {
            com.sporez.actualist.android.ui.components.ScreenPullToRefresh("budget-refresh", onRefresh) {
                BudgetScreenContent(viewModel, onShowCategoryTransactions, onOpenHistory, onOpenSchedules, onOpenSettings, onShowCategoryMonth)
            }
            androidx.compose.material3.FloatingActionButton(
                onClick = onAddTransaction,
                modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp).testTag("budget-add-transaction"),
            ) {
                Icon(androidx.compose.material.icons.Icons.Filled.Add, contentDescription = "Add transaction")
            }
        }
    }
}

@Composable
private fun BudgetScreenContent(
    viewModel: BudgetViewModel,
    onShowCategoryTransactions: (String, String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSchedules: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowCategoryMonth: ((String, String, String) -> Unit)?,
) {
    val state by viewModel.state.collectAsState()
    val month by viewModel.month.collectAsState()
    val message by viewModel.messages.message.collectAsState()
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var showToBudget by remember { mutableStateOf(false) }
    var addingCategory by remember { mutableStateOf(false) }
    var managingGroup by remember { mutableStateOf<String?>(null) }
    // A later month's header menu switches to that month first; the action opens once the month has loaded.
    var pendingMonthAction by remember { mutableStateOf<Pair<String, String>?>(null) }
    var applyingTemplates by remember { mutableStateOf(false) }
    var choosingMonth by remember { mutableStateOf(false) }
    var reviewingCover by remember { mutableStateOf(false) }
    var reviewingUncategorized by remember { mutableStateOf(false) }
    var editingMonthNote by remember { mutableStateOf(false) }
    var browsingTemplates by remember { mutableStateOf(false) }
    var unlocking by remember { mutableStateOf(false) }
    val sync by viewModel.syncStatus.collectAsState()
    val needsUnlock = (sync as? com.sporez.actualist.core.store.SyncStatus.Failed)?.message?.contains("needs its password") == true
    val server = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.sporez.actualist.android.ActualistApplication).container.server
    val appSettings = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.sporez.actualist.android.ActualistApplication).container.settings
    val display by appSettings.settings.collectAsState()
    // Show hidden categories and the collapsed groups are remembered across launches (the groups per budget).
    val showHidden = display.showHiddenCategories
    val budgetId = display.selectedBudgetId
    val collapsed = remember(budgetId) {
        mutableStateMapOf<String, Boolean>().also { map ->
            // Entries are groups toggled away from their default: "+id" for a default-collapsed group, the bare id otherwise.
            appSettings.collapsedCategoryGroups(budgetId).forEach { map[it] = true }
        }
    }
    val uncategorizedState by viewModel.uncategorized.collectAsState()
    val uncategorizedCount = (uncategorizedState as? Loadable.Ready)?.value?.size ?: 0
    // Actualist's Months Shown: Auto is three months (the shown one and two more); a fixed count of N shows N.
    val followingCount = if (display.monthsShown == 0) 2 else display.monthsShown - 1

    Column(Modifier.fillMaxSize().testTag("budget-screen")) {
        MonthHeader(
            month,
            onPrevious = viewModel::previousMonth,
            onNext = viewModel::nextMonth,
            onChoose = { choosingMonth = true },
            onToday = viewModel::showToday,
            actions = {
                BudgetActionsMenu(
                    onHistory = onOpenHistory,
                    onSchedules = onOpenSchedules,
                    onNotes = { editingMonthNote = true },
                    onTemplates = { browsingTemplates = true },
                    onSettings = onOpenSettings,
                )
            },
        )
        if (choosingMonth) {
            MonthChooserDialog(month, onPick = { picked -> viewModel.showMonth(picked); choosingMonth = false }, onDismiss = { choosingMonth = false })
        }
        when (val current = state) {
            Loadable.Loading -> Box(Modifier.fillMaxSize().testTag("budget-loading"), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("Loading budget", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is Loadable.Failed -> ErrorBanner(current.message, Modifier.padding(16.dp))
            is Loadable.Ready -> {
                val sample = LocalSampleValues.current
                val includeRollover = display.includeRolloverInAlerts
                val screen = remember(current.value, sample, includeRollover) {
                    val base = current.value.copy(includeRollover = includeRollover)
                    if (sample) base.copy(month = SampleValues.project(base.month), note = null) else base
                }
                androidx.compose.runtime.LaunchedEffect(screen.month.month, pendingMonthAction) {
                    pendingMonthAction?.let { (target, action) ->
                        if (target == screen.month.month) {
                            if (action == "notes") editingMonthNote = true else applyingTemplates = true
                            pendingMonthAction = null
                        }
                    }
                }
                // Wide windows (tablets, landscape) show the next months beside this one, as Actualist's iPad layout.
                androidx.compose.foundation.layout.BoxWithConstraints(
                    Modifier.fillMaxSize().monthSwipe(display.swipeBetweenMonths, onPrevious = viewModel::previousMonth, onNext = viewModel::nextMonth),
                ) {
                // Wide is a property of the window (as the navigation rail decides it), not of the
                // width left after the rail, so the months column survives the rail.
                val wide = com.sporez.actualist.android.ui.isWideShell() && maxWidth >= 600.dp && followingCount > 0
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
                    if (needsUnlock) {
                        item(key = "unlock") {
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().clickable { unlocking = true }.testTag("unlock-banner")) {
                                Text("This encrypted budget needs its password to sync. Tap to unlock.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                    if (screen.month.table == BudgetTable.ENVELOPE) {
                        item(key = "to-budget") { ToBudgetBanner(screen.month.toBudget, screen.month.forNextMonth) { showToBudget = true } }
                    } else {
                        screen.month.trackingSummary?.let { summary ->
                            item(key = "savings") { BudgetSavingsBanner(summary, screen.month.month, Months.current()) }
                        }
                        item(key = "tracking") { TrackingSummaryCard(screen) }
                    }
                    screen.note?.let { note ->
                        item(key = "note") {
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                                MarkdownText(note, Modifier.padding(12.dp).testTag("month-note"), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        val toggleKey = groupToggleKey(group, screen.month.table)
                        val isCollapsed = isGroupCollapsed(group, screen.month.table, collapsed)
                        item(key = "group-${group.id}") {
                            GroupHeader(group, isCollapsed, screen.month.table == BudgetTable.TRACKING, onManage = { managingGroup = group.id }) {
                                val toggled = collapsed[toggleKey] != true
                                collapsed[toggleKey] = toggled
                                appSettings.setCategoryGroupCollapsed(budgetId, toggleKey, toggled)
                            }
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
                    if (screen.month.categoryGroups.none { group -> (showHidden || !group.hidden) && group.categories.any { showHidden || !it.hidden } }) {
                        item(key = "empty") {
                            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag("budget-empty"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("This budget has no visible categories", style = MaterialTheme.typography.titleMedium)
                                Text("Add a category, or show hidden categories.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item(key = "show-hidden") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Show hidden categories", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Switch(checked = showHidden, onCheckedChange = { value -> appSettings.update { it.copy(showHiddenCategories = value) } }, modifier = Modifier.testTag("show-hidden-switch"))
                        }
                    }
                    item(key = "add-category") {
                        TextButton(onClick = { addingCategory = true }, modifier = Modifier.testTag("add-category")) { Text("Add category") }
                    }
                }
                if (wide) {
                    FollowingMonths(
                        screen,
                        viewModel,
                        collapsed,
                        Modifier.weight(maxOf(0.6f, followingCount / 2f)).fillMaxHeight(),
                        onMonthAction = { target, action ->
                            // Notes and Apply Template act on the shown month: switch to it, then open the action.
                            pendingMonthAction = target to action
                            viewModel.showMonth(target)
                        },
                    ) { month, categoryId ->
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
                            if (onShowCategoryMonth != null) onShowCategoryMonth(category.id, category.name, screen.month.month) else onShowCategoryTransactions(category.id, category.name)
                        },
                    )
                }
                if (editingMonthNote) MonthNoteDialog(screen.month.month, screen.note, viewModel) { editingMonthNote = false }
                if (browsingTemplates) TemplatesBrowserDialog(screen, viewModel) { browsingTemplates = false }
                if (applyingTemplates) TemplatesDialog(viewModel) { applyingTemplates = false }
                if (unlocking) EncryptedBudgetUnlockDialog(viewModel, server) { unlocking = false }
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
private fun MonthHeader(month: String, onPrevious: () -> Unit, onNext: () -> Unit, onChoose: () -> Unit, onToday: () -> Unit, actions: @Composable () -> Unit) {
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
        actions()
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
private fun GroupHeader(group: BudgetMonthCategoryGroup, collapsed: Boolean, tracking: Boolean, onManage: () -> Unit, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(top = 6.dp, start = 4.dp, end = 4.dp).testTag("group-${group.id}"), verticalAlignment = Alignment.Bottom) {
        Icon(if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, contentDescription = if (collapsed) "Expand" else "Collapse", modifier = Modifier.size(20.dp))
        Text(group.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(horizontalAlignment = Alignment.End) {
            Text(if (group.isIncome && !tracking) "Received" else if (tracking) "Budgeted" else "Assigned", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(money(if (group.isIncome && !tracking) group.spent else group.budgeted), style = MoneyTextStyle)
        }
        if (!group.isIncome || tracking) {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(110.dp)) {
                Text(if (!tracking) "Available" else if (group.isIncome) "Received" else "Balance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(money(if (tracking && group.isIncome) group.spent else group.balance), style = MoneyTextStyle)
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
    var picking by remember { mutableStateOf<String?>(null) }
    var managing by remember { mutableStateOf(false) }
    val envelope = screen.month.table == BudgetTable.ENVELOPE
    val groupHidden = screen.month.categoryGroups.firstOrNull { it.id == category.groupId }?.hidden == true
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp).testTag("category-sheet"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(category.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (!category.isIncome) BalancePill(category.balance)
            }
            Row {
                Text("Spent this month", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                MoneyText(category.spent)
            }
            if (!category.isIncome || !envelope) {
                AmountField(assignText, { assignText = it }, label = "${if (envelope) "Assigned" else "Budgeted"} in ${monthTitle(screen.month.month)}", tag = "assign-field", expressions = true)
                Button(
                    onClick = { MoneyExpression.evaluate(assignText)?.let { viewModel.assign(category.id, it); onDismiss() } },
                    enabled = MoneyExpression.evaluate(assignText) != null,
                    modifier = Modifier.fillMaxWidth().testTag("assign-save"),
                ) { Text("Save assignment") }
            }
            if (envelope && !category.isIncome) {
                if (category.balance < 0) {
                    OutlinedButton(onClick = { picking = "cover" }, modifier = Modifier.fillMaxWidth().testTag("cover-button")) {
                        Text("Cover ${money(-category.balance)} overspending")
                    }
                }
                OutlinedButton(
                    onClick = { picking = "move" },
                    modifier = Modifier.fillMaxWidth().testTag("move-button"),
                ) { Text("Move money…") }
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
                    TextButton(onClick = { viewModel.changeVisibility(category.id, false, category.isIncome, groupHidden); onDismiss() }, modifier = Modifier.testTag("unhide-category")) { Text("Unhide") }
                } else {
                    TextButton(onClick = { viewModel.changeVisibility(category.id, true, category.isIncome, groupHidden); onDismiss() }, modifier = Modifier.testTag("hide-category")) { Text("Hide category") }
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
        "move" -> MoveMoneyDialog(category, screen, viewModel, onDone = { picking = null; onDismiss() }, onDismiss = { picking = null })
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
    var showBrowser by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    val month = screen.month
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp).testTag("to-budget-sheet"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(monthTitle(month.month), style = MaterialTheme.typography.titleLarge)
            SummaryLine("Available funds", month.incomeAvailable)
            SummaryLine("Overspent last month", month.lastMonthOverspent)
            SummaryLine("Assigned", -month.totalBudgeted)
            SummaryLine("For next month", -month.forNextMonth)
            HorizontalDivider()
            SummaryLine("To Budget", month.toBudget)
            Spacer(Modifier.height(4.dp))
            AmountField(amountText, { amountText = it }, label = "Amount", tag = "to-budget-amount-field", expressions = true)
            val amount = MoneyExpression.evaluate(amountText) ?: 0
            OutlinedButton(onClick = { picking = true }, enabled = amount > 0, modifier = Modifier.fillMaxWidth().testTag("assign-from-to-budget")) { Text("Assign to a category…") }
            OutlinedButton(onClick = { holding = true }, modifier = Modifier.fillMaxWidth().testTag("hold-button")) { Text("Hold for next month…") }
            HorizontalDivider()
            Text("Whole month", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(onClick = { viewModel.copyPreviousMonth(); onDismiss() }, modifier = Modifier.testTag("copy-previous-month")) { Text("Copy last month") }
                TextButton(onClick = { viewModel.setThreeMonthAverage(); onDismiss() }, modifier = Modifier.testTag("three-month-average")) { Text("3-month avg") }
                TextButton(onClick = { viewModel.setZero(); onDismiss() }, modifier = Modifier.testTag("set-zero")) { Text("Zero all") }
            }
            Row {
                TextButton(onClick = { showTemplates = true }, modifier = Modifier.testTag("templates-open")) { Text("Apply budget templates…") }
                TextButton(onClick = { showBrowser = true }, modifier = Modifier.testTag("templates-browse")) { Text("Browse") }
            }
        }
    }
    if (holding) HoldDialog(screen, amountText, viewModel, onDone = { holding = false; onDismiss() }, onDismiss = { holding = false })
    if (showTemplates) TemplatesDialog(viewModel) { showTemplates = false; onDismiss() }
    if (showBrowser) TemplatesBrowserDialog(screen, viewModel) { showBrowser = false }
    if (picking) {
        PickerDialog(
            title = "Assign to",
            options = month.categoryGroups.filter { !it.isIncome }.flatMap { g -> g.categories.map { PickerOption(it.id, it.name, "${g.name} · ${money(it.balance)}") } },
            onPick = { option ->
                picking = false
                MoneyExpression.evaluate(amountText)?.let { viewModel.move(null, option.id, it) }
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


/** The category's note text (template directives in the same note are kept as they are). */
@Composable
private fun CategoryNoteField(categoryId: String, viewModel: BudgetViewModel) {
    var original by remember(categoryId) { mutableStateOf<String?>(null) }
    var text by remember(categoryId) { mutableStateOf("") }
    var loaded by remember(categoryId) { mutableStateOf(false) }
    if (LocalSampleValues.current) {
        NotesHiddenMessage()
        return
    }
    androidx.compose.runtime.LaunchedEffect(categoryId) {
        original = runCatching { viewModel.categoryNote(categoryId) }.getOrNull()
        text = original.orEmpty()
        loaded = true
    }
    if (!loaded) return
    androidx.compose.material3.OutlinedTextField(text, { text = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth().testTag("category-notes"))
    MarkdownHelp()
    if (text.isNotBlank()) MarkdownText(text, Modifier.fillMaxWidth().testTag("category-notes-rendered"), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onMonthAction: (month: String, action: String) -> Unit,
    onEdit: (month: String, categoryId: String) -> Unit,
) {
    val state by viewModel.following.collectAsState()
    val sample = LocalSampleValues.current
    val months = (state as? Loadable.Ready)?.value?.let { real -> if (sample) real.map { SampleValues.project(it) } else real } ?: return
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
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { viewModel.showMonth(month.month) }, modifier = Modifier.weight(1f).testTag("following-${month.month}")) {
                            Text(monthTitle(month.month), maxLines = 1)
                        }
                        FollowingMonthMenu(month.month, onMonthAction)
                    }
                }
            }
        }
        for (group in screen.month.categoryGroups.filter { !it.hidden }) {
            item(key = "group-${group.id}") {
                Text(group.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            }
            if (!isGroupCollapsed(group, screen.month.table, collapsed)) {
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

/** A later month's header menu in the wide grid: Notes and Apply Template for that month (Actualist's month header menu). */
@Composable
private fun FollowingMonthMenu(month: String, onAction: (month: String, action: String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(28.dp).testTag("following-menu-$month")) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${monthTitle(month)}", modifier = Modifier.size(16.dp))
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Notes") },
                onClick = { open = false; onAction(month, "notes") },
                modifier = Modifier.testTag("following-notes-$month"),
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Apply Template") },
                onClick = { open = false; onAction(month, "template") },
                modifier = Modifier.testTag("following-template-$month"),
            )
        }
    }
}

@Composable
private fun AssignInMonthDialog(month: String, category: BudgetMonthCategory, viewModel: BudgetViewModel, onDismiss: () -> Unit) {
    var text by rememberSaveable(month, category.id) { mutableStateOf(Money.format(category.budgeted, symbol = "", grouping = false)) }
    val amount = MoneyExpression.evaluate(text)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(category.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("following-assign-dialog")) {
                Text(monthTitle(month), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AmountField(text, { text = it }, label = "Assigned", tag = "following-assign-field", expressions = true)
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
