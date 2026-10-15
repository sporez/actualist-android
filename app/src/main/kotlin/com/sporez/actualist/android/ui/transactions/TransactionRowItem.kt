package com.sporez.actualist.android.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sporez.actualist.android.ui.components.MoneyText
import com.sporez.actualist.android.ui.components.money
import com.sporez.actualist.android.ui.theme.LocalMoneyColors
import com.sporez.actualist.core.transactions.RowStatus
import com.sporez.actualist.core.transactions.SplitRemainingPresentation
import com.sporez.actualist.core.transactions.TransactionRow
import com.sporez.actualist.core.transactions.TransactionRowPresentation

private val statusSlot = 18.dp

/**
 * A feed row as Actualist draws it: a transfer arrow beside the payee, the category in a chip with its
 * emoji as a separate glyph, a split parent's "left / over" remainder, and the cleared "C" or reconciled
 * lock after the amount (uncleared rows show nothing but keep the space).
 */
@Composable
internal fun TransactionItem(
    row: TransactionRow,
    showAccount: Boolean,
    isSelected: Boolean,
    isNew: Boolean,
    onOpen: (TransactionRow) -> Unit,
    onLongPress: (TransactionRow) -> Unit,
    menu: (@Composable () -> Unit)? = null,
) {
    val background = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
    val payee = TransactionRowPresentation.payee(row)
    val remaining = TransactionRowPresentation.splitRemaining(row)
    val remainingMoney = remaining?.let { money(kotlin.math.abs(it)) }.orEmpty()
    val remainingText = remaining?.let { SplitRemainingPresentation.statusText(it, row.amount) { _ -> remainingMoney } }
    val categoryText = when {
        row.isParent -> "Split"
        row.isTransfer && row.categoryName == null -> "Account Transfer"
        row.categoryName != null -> row.categoryName!!
        row.startingBalance -> "Starting balance"
        else -> "Uncategorized"
    }
    val categoryItalic = row.isParent || row.categoryName == null
    val parts = TransactionRowPresentation.categoryNameParts(categoryText)
    Column(
        Modifier.fillMaxWidth()
            .background(background)
            .combinedClickable(onClick = { onOpen(row) }, onLongClick = { onLongPress(row) })
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("transaction-${row.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.isTransfer) {
                val inflow = row.amount > 0
                Icon(
                    if (inflow) Icons.AutoMirrored.Filled.ArrowBack else Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = if (inflow) "Transfer in" else "Transfer out",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp).testTag("transfer-direction-${row.id}"),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                payee.text,
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = if (payee.placeholder) FontStyle.Italic else FontStyle.Normal,
                color = if (payee.placeholder) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (isNew) {
                Text("New", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp).testTag("new-marker-${row.id}"))
            }
            MoneyText(row.amount, colored = true)
            Spacer(Modifier.width(6.dp))
            StatusMarker(row)
            menu?.invoke()
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.testTag("category-chip-${row.id}")) {
                Row(Modifier.padding(horizontal = 9.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    parts.emoji?.let { emoji ->
                        Text(emoji, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("category-emoji-${row.id}"))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        parts.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = if (categoryItalic) FontStyle.Italic else FontStyle.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val rest = buildList {
                if (showAccount) add(row.accountName)
                row.notes?.takeIf { it.isNotBlank() }?.let(::add)
            }.joinToString(" · ")
            if (rest.isNotEmpty()) {
                Text(
                    rest,
                    Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        remainingText?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp).testTag("split-status-${row.id}"),
            )
        }
        if (row.isParent) {
            for (child in row.children) {
                Row(Modifier.padding(start = 12.dp, top = 2.dp)) {
                    Text("↳ ${child.categoryName ?: "Uncategorized"}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyText(child.amount, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** The green "C" for a cleared row, the lock for a reconciled one, and an empty slot for an uncleared one. */
@Composable
private fun StatusMarker(row: TransactionRow) {
    val green = LocalMoneyColors.current.positive
    when (TransactionRowPresentation.status(row)) {
        RowStatus.RECONCILED -> Icon(
            Icons.Filled.Lock,
            contentDescription = "Reconciled",
            tint = green,
            modifier = Modifier.size(statusSlot - 2.dp).testTag("reconciled-marker-${row.id}"),
        )
        RowStatus.CLEARED -> Surface(
            shape = CircleShape,
            color = green,
            modifier = Modifier.size(statusSlot - 2.dp).testTag("cleared-marker-${row.id}"),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("C", fontSize = 10.sp, color = MaterialTheme.colorScheme.surface)
            }
        }
        RowStatus.UNCLEARED -> Spacer(Modifier.size(statusSlot - 2.dp))
    }
}
