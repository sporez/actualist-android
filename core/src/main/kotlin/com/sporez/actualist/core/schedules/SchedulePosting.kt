package com.sporez.actualist.core.schedules

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.rules.RuleEngine
import com.sporez.actualist.core.rules.RuleRunResult
import com.sporez.actualist.core.rules.RuleTransactionInput
import com.sporez.actualist.core.rules.ScheduleRecurrence
import com.sporez.actualist.core.sync.SyncValue
import com.sporez.actualist.core.transactions.SplitDraft
import com.sporez.actualist.core.transactions.TransactionDraft
import com.sporez.actualist.core.transactions.TransactionWrites
import java.time.LocalDate
import java.util.UUID

/** The transaction a schedule occurrence posts, before the rules run. */
data class SchedulePostDraft(
    val accountId: String,
    val date: LocalDate,
    val amount: Long,
    val payeeId: String?,
    val payeeName: String,
    val categoryId: String? = null,
    val notes: String? = null,
    val cleared: Boolean = false,
    val scheduleId: String?,
)

data class SchedulePostingReceipt(
    val scheduleId: String,
    val transactionId: String,
    val occurrenceDayId: String,
    val postedDayId: String,
    val appliedMessageCount: Int,
    val affectedAccountIds: List<String>,
    val affectedTransactionIds: List<String>,
    val affectedMonthIds: List<String>,
)

/**
 * Posting a schedule occurrence as a transaction. Port of Actualist `BudgetDatabase+SchedulePosting`.
 * The posted row passes through the rule engine first (like Actual, a matching rule may change
 * it), is linked to the schedule, and is written with [TransactionWrites] cells in the same
 * transaction that re-validates the schedule review.
 */
class SchedulePosting(
    private val database: BudgetDatabase,
    private val clock: Clock = Clock.SYSTEM,
    private val ruleEngine: RuleEngine = RuleEngine(database),
    private val transactions: TransactionWrites = TransactionWrites(database, clock),
) {
    private val reads = ScheduleReads(database)
    private val sql get() = database.sql

    private class Graph(
        val drafts: List<CellDraft>,
        val primaryScheduleId: String?,
        val affectedAccountIds: List<String>,
        val affectedTransactionIds: List<String>,
    )

    private class Projected(
        val accountId: String,
        val date: LocalDate,
        val amount: Long,
        val payeeId: String?,
        val categoryId: String?,
        val notes: String?,
        val cleared: Boolean,
        val scheduleId: String?,
        val splits: List<com.sporez.actualist.core.rules.RuleSplitResult>,
    )

    /**
     * Posts the occurrence on [postedDayId] (the schedule's next date, or [today] for "post today").
     * Throws [SchedulePostingRefusalException] for deterministic refusals and
     * [ScheduleMutationCommandException] for review preconditions.
     */
    fun postScheduleOccurrence(
        review: ScheduleMutationReview,
        draft: SchedulePostDraft,
        transactionId: String,
        postedDayId: String,
        today: String,
        nowMillis: Long = clock.nowMillis(),
    ): SchedulePostingReceipt {
        if (transactionId.isEmpty() || draft.scheduleId != review.scheduleId ||
            ScheduleRecurrence.parseDay(postedDayId) == null || ScheduleRecurrence.parseDay(today) == null
        ) {
            throw SchedulePostingRefusalException(SchedulePostingRefusal.INVALID_COMMAND)
        }
        val committed = database.commitLocal(nowMillis) {
            // Schema gaps and deleted or closed referenced rows are deterministic per schedule, so
            // they are refusals rather than errors that would stop automatic advancement.
            try {
                plan(review, draft, transactionId, postedDayId, today, nowMillis)
            } catch (e: LocalFirstException.SchemaUnavailable) {
                throw SchedulePostingRefusalException(SchedulePostingRefusal.UNSUPPORTED_BUDGET_SCHEMA)
            } catch (e: LocalFirstException.InvalidLocalWrite) {
                if (isReferencedRowFailure(e)) {
                    throw SchedulePostingRefusalException(SchedulePostingRefusal.REFERENCED_ROW_UNAVAILABLE)
                }
                throw e
            }
        }
        return committed.outcome.copy(appliedMessageCount = committed.messages.size)
    }

    /**
     * Posts a schedule's current occurrence the way the schedules screen does: the schedule's own
     * account, payee and posting amount, dated at its next date or, with [postsToday], at [today].
     */
    fun postSchedule(
        scheduleId: String,
        today: String,
        postsToday: Boolean = false,
        nowMillis: Long = clock.nowMillis(),
        transactionId: String = UUID.randomUUID().toString(),
    ): SchedulePostingReceipt {
        val review = reads.scheduleMutationReview(scheduleId)
        val detail = reads.fetchScheduleDetail(scheduleId, today)
        val accountId = detail?.account?.id
        val amount = detail?.amount?.postingAmount
        val scheduled = detail?.effectiveNextDate
        if (detail == null || accountId == null || amount == null || scheduled == null) {
            throw SchedulePostingRefusalException(SchedulePostingRefusal.UNSUPPORTED_OCCURRENCE)
        }
        val postedDayId = if (postsToday) today else scheduled
        val date = ScheduleRecurrence.parseDay(postedDayId)
            ?: throw SchedulePostingRefusalException(SchedulePostingRefusal.INVALID_COMMAND)
        val draft = SchedulePostDraft(
            accountId = accountId,
            date = date,
            amount = amount,
            payeeId = detail.payee.postingPayeeId,
            payeeName = detail.payee.name ?: "",
            scheduleId = detail.id,
        )
        return postScheduleOccurrence(review, draft, transactionId, postedDayId, today, nowMillis)
    }

    private fun isReferencedRowFailure(error: LocalFirstException.InvalidLocalWrite): Boolean =
        error.reason.startsWith("missing") || error.reason.endsWith("closed or unavailable")

    private fun plan(
        review: ScheduleMutationReview,
        draft: SchedulePostDraft,
        transactionId: String,
        postedDayId: String,
        today: String,
        nowMillis: Long,
    ): Pair<List<CellDraft>, SchedulePostingReceipt> {
        val current = reads.validateReview(review)
        val accountId = current.projection.accountId
        val accountColumns = if (database.tableExists("accounts")) database.columnSet("accounts") else emptySet()
        if (accountId == null || "id" !in accountColumns) {
            throw SchedulePostingRefusalException(SchedulePostingRefusal.ACCOUNT_UNAVAILABLE)
        }
        val closed = if ("closed" in accountColumns) "closed" else "0"
        val tombstone = if ("tombstone" in accountColumns) "tombstone" else "0"
        val accountRow = sql.queryOne("SELECT $closed AS closed, $tombstone AS tombstone FROM accounts WHERE id = ? LIMIT 1", accountId)
        if (accountRow == null || accountRow.bool("closed") || accountRow.bool("tombstone")) {
            throw SchedulePostingRefusalException(SchedulePostingRefusal.ACCOUNT_UNAVAILABLE)
        }

        val schedule = reads.fetchScheduleDetail(review.scheduleId, today)
        if (schedule == null || !schedule.capabilities.canPost || !schedule.status.allowsManualPosting ||
            schedule.account.availability != ScheduleReferenceAvailability.AVAILABLE ||
            current.projection.amount.postingAmount == null ||
            current.review.schedule.completed || current.review.schedule.tombstone || current.review.rule.tombstone
        ) {
            throw SchedulePostingRefusalException(SchedulePostingRefusal.OCCURRENCE_UNAVAILABLE)
        }
        val expectedDay: String
        if (postedDayId == today) {
            expectedDay = today
        } else {
            val effectiveDate = current.effectiveNextDate
            if (effectiveDate == null || postedDayId != effectiveDate) {
                throw SchedulePostingRefusalException(SchedulePostingRefusal.OCCURRENCE_DATE_MISMATCH)
            }
            expectedDay = effectiveDate
        }
        if (ScheduleRecurrence.parseDay(expectedDay) == null) {
            throw SchedulePostingRefusalException(SchedulePostingRefusal.OCCURRENCE_UNAVAILABLE)
        }
        if (Months.packedDate(draft.date).toLong() != expectedDay.replace("-", "").toLong() || draft.scheduleId != review.scheduleId) {
            throw SchedulePostingRefusalException(SchedulePostingRefusal.DRAFT_MISMATCH)
        }

        val todayDate = ScheduleRecurrence.parseDay(today)
            ?: throw SchedulePostingRefusalException(SchedulePostingRefusal.INVALID_COMMAND)
        val initialTransfer = draft.payeeId?.let { transactions.transferAccountIdOf(it) != null } ?: false
        val evaluated = ruleEngine.run(ruleInput(draft, initialTransfer), todayDate)
        if (evaluated.deletesTransaction) {
            throw SchedulePostingRefusalException(SchedulePostingRefusal.RULE_DELETES_TRANSACTION)
        }
        val projected = project(evaluated, draft)
        val finalTransfer = projected.payeeId?.let { transactions.transferAccountIdOf(it) != null } ?: false
        // Actual's scheduled post sets sort_order to the clock in milliseconds. Transfer and split
        // posts keep their own writers' ordering.
        val sortOrder: Double? = if (finalTransfer || projected.splits.isNotEmpty()) null else nowMillis.toDouble()
        val graph = transactionGraph(projected, finalTransfer, draft.payeeName, transactionId, sortOrder, todayDate)
        if (graph.primaryScheduleId != review.scheduleId) {
            throw SchedulePostingRefusalException(SchedulePostingRefusal.RULE_CHANGED_SCHEDULE_LINK)
        }
        val occurrenceDate = schedule.effectiveNextDate
            ?: throw SchedulePostingRefusalException(SchedulePostingRefusal.OCCURRENCE_UNAVAILABLE)
        val receipt = SchedulePostingReceipt(
            scheduleId = review.scheduleId,
            transactionId = transactionId,
            occurrenceDayId = occurrenceDate,
            postedDayId = ScheduleRecurrence.dayId(projected.date),
            appliedMessageCount = 0,
            affectedAccountIds = graph.affectedAccountIds,
            affectedTransactionIds = graph.affectedTransactionIds,
            affectedMonthIds = listOf(Months.id(projected.date.year, projected.date.monthValue)),
        )
        return graph.drafts to receipt
    }

    private fun ruleInput(draft: SchedulePostDraft, isTransfer: Boolean) = RuleTransactionInput(
        accountId = draft.accountId,
        date = draft.date,
        amount = draft.amount,
        payeeId = draft.payeeId,
        payeeName = draft.payeeName,
        categoryId = draft.categoryId,
        notes = draft.notes,
        cleared = draft.cleared,
        isTransfer = isTransfer,
        scheduleId = draft.scheduleId,
    )

    /** Actualist `TransactionRulePreviewProjection.applying`: the rule result is the final draft. */
    private fun project(result: RuleRunResult, draft: SchedulePostDraft) = Projected(
        accountId = result.accountId,
        date = result.date,
        amount = result.amount,
        payeeId = result.payeeId,
        categoryId = if (result.splits.isEmpty()) result.categoryId else null,
        notes = result.notes,
        cleared = result.cleared,
        scheduleId = result.scheduleId ?: draft.scheduleId,
        splits = result.splits,
    )

    private fun transactionGraph(
        projected: Projected,
        isTransfer: Boolean,
        payeeName: String,
        transactionId: String,
        sortOrder: Double?,
        today: LocalDate,
    ): Graph {
        if (projected.accountId.isEmpty()) throw LocalFirstException.InvalidLocalWrite("missing account")
        val columns = database.requiredColumns("transactions", listOf("date", "amount", "category"))
        if ("schedule" !in columns) throw LocalFirstException.SchemaUnavailable("transactions.schedule is unavailable")
        if (database.tableExists("accounts") && !database.liveRowExists("accounts", projected.accountId)) {
            throw LocalFirstException.InvalidLocalWrite("missing account")
        }
        validatePostingAccount(projected.accountId)

        if (isTransfer) {
            val payeeId = projected.payeeId ?: throw LocalFirstException.InvalidLocalWrite("missing transfer payee")
            val destinationAccountId = transactions.transferAccountIdOf(payeeId)
                ?: throw LocalFirstException.InvalidLocalWrite("selected payee is not a transfer payee")
            val destinationPayeeId = sql.queryString(
                "SELECT id FROM payees WHERE transfer_acct = ? AND ${BudgetDatabase.livePredicate(database.columnSet("payees"))} LIMIT 1",
                projected.accountId,
            ) ?: throw LocalFirstException.InvalidLocalWrite("missing transfer payee for account")
            val pairedInput = RuleTransactionInput(
                accountId = destinationAccountId,
                date = projected.date,
                amount = -projected.amount,
                payeeId = destinationPayeeId,
                payeeName = sql.queryString("SELECT name FROM payees WHERE id = ?", destinationPayeeId) ?: "",
                categoryId = null,
                notes = projected.notes,
                cleared = false,
                isTransfer = true,
                scheduleId = projected.scheduleId,
            )
            val paired = ruleEngine.run(pairedInput, today)
            val pairedScheduleId = paired.scheduleId ?: projected.scheduleId
            val plan = transactions.transferDrafts(
                TransactionDraft(
                    accountId = projected.accountId,
                    date = projected.date,
                    amount = projected.amount,
                    payeeId = payeeId,
                    payeeName = payeeName,
                    categoryId = projected.categoryId,
                    notes = projected.notes,
                    cleared = projected.cleared,
                    isTransfer = true,
                    sortOrder = sortOrder,
                ),
                transactionId,
                payeeId,
            )
            val pairedId = plan.pairedTransactionId
            val cells = plan.drafts
                .filterNot { it.row == pairedId && (it.column == "notes" || it.column == "cleared") }
                .toMutableList()
            cells.add(CellDraft("transactions", pairedId, "cleared", SyncValue.bool(paired.cleared)))
            paired.notes?.let { cells.add(CellDraft("transactions", pairedId, "notes", SyncValue.text(it))) }
            if (pairedScheduleId != null) {
                cells.add(CellDraft("transactions", transactionId, "schedule", SyncValue.text(pairedScheduleId)))
                cells.add(CellDraft("transactions", pairedId, "schedule", SyncValue.text(pairedScheduleId)))
            }
            validatePostingAccount(plan.destinationAccountId)
            return Graph(cells, pairedScheduleId, listOf(projected.accountId, plan.destinationAccountId), listOf(transactionId, pairedId))
        }

        if (projected.splits.isNotEmpty()) {
            val cells = transactions.splitDrafts(
                TransactionDraft(
                    accountId = projected.accountId,
                    date = projected.date,
                    amount = projected.amount,
                    payeeId = projected.payeeId,
                    payeeName = payeeName,
                    categoryId = null,
                    notes = projected.notes,
                    cleared = projected.cleared,
                    sortOrder = sortOrder,
                    splits = projected.splits.map { SplitDraft(it.categoryId, it.amount, it.notes) },
                ),
                transactionId,
                projected.payeeId,
            ).toMutableList()
            projected.scheduleId?.let { cells.add(CellDraft("transactions", transactionId, "schedule", SyncValue.text(it))) }
            val ids = listOf(transactionId) + cells.map { it.row }.distinct().filter { it != transactionId }
            return Graph(cells, projected.scheduleId, listOf(projected.accountId), ids)
        }

        val cells = transactions.simpleDrafts(
            TransactionDraft(
                accountId = projected.accountId,
                date = projected.date,
                amount = projected.amount,
                payeeId = projected.payeeId,
                payeeName = payeeName,
                categoryId = projected.categoryId,
                notes = projected.notes,
                cleared = projected.cleared,
                sortOrder = sortOrder,
            ),
            transactionId,
            projected.payeeId,
        ).toMutableList()
        projected.scheduleId?.let { cells.add(CellDraft("transactions", transactionId, "schedule", SyncValue.text(it))) }
        return Graph(cells, projected.scheduleId, listOf(projected.accountId), listOf(transactionId))
    }

    private fun validatePostingAccount(accountId: String) {
        val columns = database.requiredColumns("accounts", listOf("id"))
        val closed = if ("closed" in columns) "closed" else "0"
        val tombstone = if ("tombstone" in columns) "tombstone" else "0"
        val row = sql.queryOne("SELECT $closed AS closed, $tombstone AS tombstone FROM accounts WHERE id = ? LIMIT 1", accountId)
        if (row == null || row.bool("closed") || row.bool("tombstone")) {
            throw LocalFirstException.InvalidLocalWrite("transaction account is closed or unavailable")
        }
    }
}
