@file:OptIn(ExperimentalMaterial3Api::class)

package com.sporez.actualist.android.ui.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.Loadable
import com.sporez.actualist.android.ui.components.BalancePill
import com.sporez.actualist.android.ui.components.MoneyText
import com.sporez.actualist.android.ui.components.PickerDialog
import com.sporez.actualist.android.ui.components.PickerOption
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.android.ui.components.money
import com.sporez.actualist.android.ui.theme.LocalMoneyColors
import com.sporez.actualist.core.transactions.TransactionRow

/** The count entry for uncategorized transactions in the shown month, as Actualist's header alert. */
@Composable
fun UncategorizedRow(count: Int, onReview: () -> Unit) {
    SectionCard(Modifier.clickable(onClick = onReview).testTag("uncategorized-row")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).background(LocalMoneyColors.current.neutralContainer, CircleShape), contentAlignment = Alignment.Center) {
                Text("$count", color = LocalMoneyColors.current.onNeutralContainer, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("uncategorized-count"))
            }
            Spacer(Modifier.width(12.dp))
            Text(if (count == 1) "Uncategorized transaction" else "Uncategorized transactions", Modifier.weight(1f))
            Text("Review", style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

/**
 * Actualist's guided cover review: every overspent category with the source that will cover it
 * (To Budget unless chosen otherwise), covered one at a time or all together. A category source
 * covers only what it has left.
 */
@Composable
fun CoverReviewSheet(screen: BudgetScreenState, viewModel: BudgetViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Category id -> chosen source category; an absent entry is To Budget.
    val sources = remember { mutableStateMapOf<String, String>() }
    var picking by remember { mutableStateOf<String?>(null) }
    val overspent = screen.overspent
    val donors = screen.month.categoryGroups.filter { !it.isIncome }
        .flatMap { group -> group.categories.map { it to group.name } }
        .filter { it.first.balance > 0 }

    fun planFor(categoryId: String, owed: Long): Pair<String?, Long> {
        val source = sources[categoryId]?.let { id -> donors.firstOrNull { it.first.id == id }?.first }
        return if (source == null) null to owed else source.id to minOf(owed, source.balance)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()).testTag("cover-review"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Cover overspending", style = MaterialTheme.typography.titleLarge)
            if (overspent.isEmpty()) {
                Text("Nothing is overspent in ${monthTitle(screen.month.month)}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("cover-review-done")) { Text("Done") }
            }
            for (category in overspent) {
                val owed = -category.balance
                val (sourceId, amount) = planFor(category.id, owed)
                val sourceName = sourceId?.let { id -> donors.firstOrNull { it.first.id == id }?.first?.name } ?: "To Budget"
                Column(Modifier.fillMaxWidth().testTag("cover-item-${category.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(category.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        BalancePill(category.balance)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { picking = category.id }, modifier = Modifier.weight(1f).testTag("cover-source-${category.id}")) {
                            Text("From $sourceName", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Button(
                            onClick = { viewModel.coverAll(listOf(Triple(category.id, sourceId, amount))) },
                            enabled = amount > 0,
                            modifier = Modifier.testTag("cover-apply-${category.id}"),
                        ) { Text(if (amount < owed) "Use all ${money(amount)}" else "Cover ${money(amount)}") }
                    }
                }
                HorizontalDivider()
            }
            if (overspent.size > 1) {
                val plans = overspent.map { category -> Triple(category.id, planFor(category.id, -category.balance).first, planFor(category.id, -category.balance).second) }
                    .filter { it.third > 0 }
                Button(
                    onClick = { viewModel.coverAll(plans); onDismiss() },
                    enabled = plans.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().testTag("cover-all"),
                ) { Text("Cover all ${overspent.size} categories") }
            }
        }
    }
    picking?.let { id ->
        PickerDialog(
            title = "Cover from",
            options = listOf(PickerOption(null, "To Budget", money(screen.month.toBudget))) +
                donors.map { (category, group) -> PickerOption(category.id, category.name, "$group · ${money(category.balance)}") },
            onPick = { option ->
                if (option.id == null) sources.remove(id) else sources[id] = option.id
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

/**
 * Actualist's uncategorized review: the shown month's uncategorized on-budget transactions, each
 * given a category in place. A reconciled transaction asks for Actualist's confirmation first,
 * then goes through the reconciled-mutation unlock and relock.
 */
@Composable
fun UncategorizedReviewSheet(viewModel: BudgetViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by viewModel.uncategorized.collectAsState()
    var options by remember { mutableStateOf<List<PickerOption>>(emptyList()) }
    var picking by remember { mutableStateOf<TransactionRow?>(null) }
    var confirming by remember { mutableStateOf<Pair<TransactionRow, String>?>(null) }
    LaunchedEffect(Unit) { options = viewModel.categoryOptions() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).testTag("uncategorized-review"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Uncategorized transactions", style = MaterialTheme.typography.titleLarge)
            when (val current = state) {
                Loadable.Loading -> Text("Reading transactions…")
                is Loadable.Failed -> Text(current.message, color = MaterialTheme.colorScheme.error)
                is Loadable.Ready -> {
                    val rows = current.value
                    if (rows.isEmpty()) {
                        Text("Every transaction in this month has a category.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("uncategorized-empty"))
                        TextButton(onClick = onDismiss, modifier = Modifier.testTag("uncategorized-done")) { Text("Done") }
                    }
                    LazyColumn(Modifier.heightIn(max = 480.dp).testTag("uncategorized-list")) {
                        items(rows, key = { it.id }) { row ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.displayPayee.ifBlank { "No payee" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                    Text("${row.date ?: ""} · ${row.accountName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                MoneyText(row.amount, colored = true)
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(
                                    onClick = { picking = row },
                                    modifier = Modifier.testTag("uncategorized-categorize"),
                                ) { Text("Categorize") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
    picking?.let { row ->
        PickerDialog(
            title = "Categorize as",
            options = options,
            onPick = { option ->
                picking = null
                option.id?.let { categoryId ->
                    if (row.reconciled) confirming = row to categoryId else viewModel.categorize(row.id, categoryId)
                }
            },
            onDismiss = { picking = null },
        )
    }
    confirming?.let { (row, categoryId) ->
        val warning = com.sporez.actualist.core.transactions.ReconciledMutationPresentation.make(
            com.sporez.actualist.core.transactions.ReconciledIntent.CATEGORIZE,
            targetReconciledCount = 1,
            includesPairedTransfer = false,
        )
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(warning.title) },
            text = { Text(warning.message, modifier = Modifier.testTag("uncategorized-reconciled-warning")) },
            confirmButton = {
                TextButton(
                    onClick = { confirming = null; viewModel.categorize(row.id, categoryId, unlockReconciled = true) },
                    modifier = Modifier.testTag("uncategorized-reconciled-confirm"),
                ) { Text(warning.confirmationTitle) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }, modifier = Modifier.testTag("uncategorized-reconciled-cancel")) { Text("Cancel") } },
        )
    }
}
