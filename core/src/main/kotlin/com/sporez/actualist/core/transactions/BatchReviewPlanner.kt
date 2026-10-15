package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.money.Money
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class BatchIntent { CLEAR, UNCLEAR, CATEGORIZE, DELETE, DUPLICATE }

/** One field a batch action changes, shown as "before → after". */
data class BatchEffect(val title: String, val before: String, val after: String)

enum class BatchRowStatus(val label: String) {
    WILL_CHANGE("Will change"),
    NO_CHANGE("No change"),
    NEEDS_CONFIRMATION("Needs confirmation"),
    SKIPPED("Skipped"),
}

data class BatchReviewRow(
    val id: String,
    val payee: String,
    val context: String,
    val amount: Long,
    val status: BatchRowStatus,
    val explanation: String?,
    val effects: List<BatchEffect>,
    val reconciled: Boolean,
)

/** What a batch action would do to the selected rows, for the review shown before it commits. */
data class BatchReview(
    val intent: BatchIntent,
    val title: String,
    val confirmationTitle: String,
    val rows: List<BatchReviewRow>,
    val selectedCount: Int,
    val changedCount: Int,
    val skippedCount: Int,
    val reconciledCount: Int,
) {
    /** Actualist asks before a batch touches reconciled rows (duplicates only copy them). */
    val needsReconciledConfirmation: Boolean get() = reconciledCount > 0 && intent != BatchIntent.DUPLICATE
    val reconciledMessage: String
        get() = "$reconciledCount reconciled transaction${if (reconciledCount == 1) " is" else "s are"} connected to these changes. " +
            "Changing ${if (reconciledCount == 1) "it" else "them"} can change a previously balanced account."
}

/**
 * Pure projection of Actualist's batch review (`TransactionBatchReviewPresentation`): which
 * selected rows change, which are skipped and why, and each row's before and after values.
 * The rules mirror [TransactionBatch] so the review matches what confirming does.
 */
object BatchReviewPlanner {
    const val HISTORY_HINT = "You can undo this in Budget → History."

    private val dayFormat: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(Locale.getDefault())

    fun plan(intent: BatchIntent, selectedIds: Collection<String>, rows: List<TransactionRow>, categoryName: String? = null): BatchReview {
        val byId = rows.associateBy { it.id }
        val ids = selectedIds.distinct()
        val reviewRows = ids.map { id ->
            val row = byId[id] ?: return@map BatchReviewRow(id, "Transaction unavailable", "", 0, BatchRowStatus.SKIPPED, "The selected transaction is unavailable.", emptyList(), false)
            review(intent, row, categoryName)
        }
        val changed = reviewRows.count { it.status == BatchRowStatus.WILL_CHANGE || it.status == BatchRowStatus.NEEDS_CONFIRMATION }
        return BatchReview(
            intent = intent,
            title = title(intent),
            confirmationTitle = confirmationTitle(intent),
            rows = reviewRows,
            selectedCount = ids.size,
            changedCount = changed,
            skippedCount = reviewRows.count { it.status == BatchRowStatus.SKIPPED },
            reconciledCount = reviewRows.count { it.reconciled && it.status == BatchRowStatus.NEEDS_CONFIRMATION },
        )
    }

    fun title(intent: BatchIntent): String = when (intent) {
        BatchIntent.CLEAR -> "Review Clear Transactions"
        BatchIntent.UNCLEAR -> "Review Unclear Transactions"
        BatchIntent.CATEGORIZE -> "Review Categorize Transactions"
        BatchIntent.DELETE -> "Review Delete Transactions"
        BatchIntent.DUPLICATE -> "Review Duplicate Transactions"
    }

    fun confirmationTitle(intent: BatchIntent): String = when (intent) {
        BatchIntent.CLEAR -> "Clear"
        BatchIntent.UNCLEAR -> "Unclear"
        BatchIntent.CATEGORIZE -> "Categorize"
        BatchIntent.DELETE -> "Delete"
        BatchIntent.DUPLICATE -> "Duplicate"
    }

    private fun clearedText(cleared: Boolean) = if (cleared) "Cleared" else "Uncleared"

    private fun review(intent: BatchIntent, row: TransactionRow, categoryName: String?): BatchReviewRow {
        fun make(status: BatchRowStatus, explanation: String? = null, effects: List<BatchEffect> = emptyList()) = BatchReviewRow(
            id = row.id,
            payee = row.displayPayee.ifBlank { "No payee" },
            context = listOfNotNull(row.date?.format(dayFormat), row.accountName).joinToString(" · "),
            amount = row.amount,
            status = status,
            explanation = explanation,
            effects = effects,
            reconciled = row.reconciled,
        )
        // Split entries are never listed on their own, but a stale selection could name one.
        if (row.isChild) return make(BatchRowStatus.SKIPPED, "A split entry changes with its whole transaction.")
        return when (intent) {
            BatchIntent.CLEAR, BatchIntent.UNCLEAR -> {
                val target = intent == BatchIntent.CLEAR
                val effect = BatchEffect("Cleared", clearedText(row.cleared), clearedText(target))
                when {
                    row.reconciled -> make(BatchRowStatus.NEEDS_CONFIRMATION, "This change includes reconciled transaction data.", listOf(effect).filter { row.cleared != target })
                    row.cleared == target -> make(BatchRowStatus.NO_CHANGE, "No stored values will change.")
                    else -> make(BatchRowStatus.WILL_CHANGE, effects = listOf(effect))
                }
            }
            BatchIntent.CATEGORIZE -> {
                val after = categoryName ?: "Category"
                val effect = BatchEffect("Category", row.categoryName ?: "Uncategorized", after)
                when {
                    row.isParent -> make(BatchRowStatus.SKIPPED, "A split transaction is categorized through its entries.")
                    row.isTransfer -> make(BatchRowStatus.SKIPPED, "A transfer has no category.")
                    row.reconciled -> make(BatchRowStatus.NEEDS_CONFIRMATION, "This change includes reconciled transaction data.", listOf(effect))
                    row.categoryName == categoryName && categoryName != null -> make(BatchRowStatus.NO_CHANGE, "No stored values will change.")
                    else -> make(BatchRowStatus.WILL_CHANGE, effects = listOf(effect))
                }
            }
            BatchIntent.DELETE -> {
                val effects = listOf(BatchEffect("Status", "Present", "Deleted"))
                val note = when {
                    row.isTransfer -> "The other side of the transfer is deleted too."
                    row.isParent -> "Its split entries are deleted too."
                    else -> null
                }
                if (row.reconciled) make(BatchRowStatus.NEEDS_CONFIRMATION, "This change includes reconciled transaction data.", effects)
                else make(BatchRowStatus.WILL_CHANGE, note, effects)
            }
            BatchIntent.DUPLICATE -> make(
                BatchRowStatus.WILL_CHANGE,
                if (row.isTransfer) "Both sides of the transfer are copied." else if (row.isParent) "Its split entries are copied too." else null,
                listOf(BatchEffect("Copy", "None", "New ${clearedText(false).lowercase()} copy")) +
                    listOfNotNull(if (row.cleared) BatchEffect("Cleared", "Cleared", "Uncleared (copy)") else null),
            )
        }
    }

    /** "$12.50" style text for the review's amounts. */
    fun amountText(amount: Long): String = Money.format(amount)
}
