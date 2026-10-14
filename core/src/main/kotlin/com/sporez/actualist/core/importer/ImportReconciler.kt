package com.sporez.actualist.core.importer

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.importer.BankSyncReconciliation.Candidate
import com.sporez.actualist.core.importer.BankSyncReconciliation.Entry
import com.sporez.actualist.core.importer.BankSyncReconciliation.Existing
import com.sporez.actualist.core.importer.BankSyncReconciliation.MatchedUpdate
import com.sporez.actualist.core.rules.RuleEngine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

enum class ImportDecisionKind {
    /** No local row matched: the row is added as a new transaction. */
    ADDED,

    /** Matched a local row and fills or changes some of its fields. */
    UPDATED,

    /** Matched a local row that is reconciled (locked) or already identical. */
    UNCHANGED,

    /** The exact provider id belongs to a deleted row, so nothing is written. */
    SKIPPED_DELETED,

    /** A delete-transaction rule matched and no local row did, so the row is dropped. */
    SKIPPED_BY_RULE,
}

/** What the reconcile decided for one incoming row. */
data class ImportRowDecision(
    /** The index of the row in the list handed to the reconciler. */
    val sourceIndex: Int,
    val kind: ImportDecisionKind,
    /** The matched local row, for [ImportDecisionKind.UPDATED] and [ImportDecisionKind.UNCHANGED]. */
    val existingId: String? = null,
    val update: MatchedUpdate? = null,
    /** The rule-projected candidate, for [ImportDecisionKind.ADDED]. */
    val candidate: Candidate? = null,
)

/** The outcome of previewing an import: per-row decisions, counts, and the underlying reconcile plan. */
data class ImportPlan(
    /** One decision per incoming row, in input order. */
    val decisions: List<ImportRowDecision>,
    val addedCount: Int,
    val updatedCount: Int,
    val unchangedCount: Int,
    /** Deleted-id skips plus rows dropped by a delete-transaction rule. */
    val skippedCount: Int,
    /** The matcher's plan over the rule-projected candidates (entry order, not input order). */
    val reconciliation: BankSyncReconciliation.Plan,
    /** The local rows the plan matched against. */
    val existing: List<Existing>,
)

/** Bank Sync's first-sync step: the account's first sync writes a Starting Balance row. */
data class OpeningBalanceRequest(val currentBalance: Long, val sortOrder: Double? = null)

data class ImportApplyResult(
    val plan: ImportPlan,
    val insertedCount: Int,
    val updatedCount: Int,
    val openingBalanceAdded: Boolean,
    val insertedIdsByAccount: Map<String, List<String>>,
    val affectedAccountIds: Set<String>,
)

/**
 * The import reconcile engine shared by CSV import and Bank Sync (Actual's `importTransactions` /
 * `reconcileTransactions`): rule projection, then matching against the account's live rows (imported id,
 * same payee within 7 days, nearest same-amount row), payee creation, and atomic CRDT writes.
 *
 * [preview] reads only. [apply] plans and writes inside one [BudgetDatabase.commitLocal], so what it writes
 * is planned from the latest committed rows. A rule that moves a row to another account fails the call
 * (`LocalFirstException.InvalidLocalWrite`): an import never writes into an account other than its own.
 */
class ImportReconciler(
    private val database: BudgetDatabase,
    private val clock: Clock = Clock.SYSTEM,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val reads = ImportReconcileReads(database)
    private val writer = ImportReconcileWriter(database, clock, newId)

    /** Today in UTC by the injected clock; the day rules and the first-sync history check use. */
    fun todayUtc(): LocalDate = Instant.ofEpochMilli(clock.nowMillis()).atZone(ZoneOffset.UTC).toLocalDate()

    /** The candidates for [rows]: payee and category names resolve against the open budget. */
    fun candidates(rows: List<ImportRow>, options: ImportReconcileOptions): List<Candidate> =
        ImportCandidates.candidates(rows, reads.lookup(), options)

    /** Plans an import without writing anything. */
    fun preview(
        accountId: String,
        rows: List<ImportRow>,
        options: ImportReconcileOptions = ImportReconcileOptions.CSV,
        today: LocalDate = todayUtc(),
    ): ImportPlan = database.sql.locked {
        reconcile(accountId, candidates(rows, options), options, today)
    }

    /** Plans and writes an import in one atomic commit. */
    fun apply(
        accountId: String,
        rows: List<ImportRow>,
        options: ImportReconcileOptions = ImportReconcileOptions.CSV,
        today: LocalDate = todayUtc(),
        sortOrderBase: Double? = null,
    ): ImportApplyResult = applyPrepared(accountId, options, today, sortOrderBase, null) { candidates(rows, options) }

    /** [preview] for candidates already built by the caller (Bank Sync rows, mapped by the provider layer). */
    fun previewCandidates(
        accountId: String,
        candidates: List<Candidate>,
        options: ImportReconcileOptions,
        today: LocalDate = todayUtc(),
    ): ImportPlan = database.sql.locked { reconcile(accountId, candidates, options, today) }

    /**
     * [apply] for candidates already built by the caller. With [openingBalance] the account's first sync also
     * writes a Starting Balance row when the account had no live transactions.
     */
    fun applyCandidates(
        accountId: String,
        candidates: List<Candidate>,
        options: ImportReconcileOptions,
        today: LocalDate = todayUtc(),
        sortOrderBase: Double? = null,
        openingBalance: OpeningBalanceRequest? = null,
    ): ImportApplyResult = applyPrepared(accountId, options, today, sortOrderBase, openingBalance) { candidates }

    private fun applyPrepared(
        accountId: String,
        options: ImportReconcileOptions,
        today: LocalDate,
        sortOrderBase: Double?,
        openingBalance: OpeningBalanceRequest?,
        buildCandidates: () -> List<Candidate>,
    ): ImportApplyResult {
        val base = sortOrderBase ?: clock.nowMillis().toDouble()
        val committed = database.commitLocal {
            val built = buildCandidates()
            val plan = reconcile(accountId, built, options, today)
            val offBudget = reads.accountIsOffBudget(accountId)
            val existingById = HashMap<String, Existing>()
            for (row in plan.existing) existingById[row.id] = row
            val updates = ArrayList<Pair<MatchedUpdate, Existing>>()
            for (entry in plan.reconciliation.entries) {
                if (entry !is Entry.Update) continue
                val row = existingById[entry.update.existingId] ?: continue
                updates.add(entry.update to row)
            }
            val writes = writer.writes(
                accountId = accountId,
                accountIsOffBudget = offBudget,
                updates = updates,
                inserts = plan.reconciliation.inserts,
                options = options,
                sortOrder = { index -> base - index.toDouble() },
            )
            val drafts = ArrayList<CellDraft>()
            var openingAdded = false
            if (openingBalance != null) {
                val hadLive = reads.oldestLiveTransactionDayId(accountId, ImportDays.dayId(today)) != null
                val opening = BankSyncReconciliation.openingBalance(
                    currentBalance = openingBalance.currentBalance,
                    inserts = plan.reconciliation.inserts,
                    earliestDayId = built.map { it.dayId }.minOrNull(),
                    accountHadLiveTransactions = hadLive,
                    todayDayId = ImportDays.dayId(today),
                )
                if (opening != null) {
                    drafts.addAll(writer.openingBalanceDrafts(accountId, opening, !offBudget, openingBalance.sortOrder ?: (base + 1.0)))
                    openingAdded = true
                }
            }
            drafts.addAll(writes.drafts)
            val result = ImportApplyResult(
                plan = plan,
                insertedCount = writes.insertedCount,
                updatedCount = writes.updatedCount,
                openingBalanceAdded = openingAdded,
                insertedIdsByAccount = writes.insertedIdsByAccount,
                affectedAccountIds = writes.affectedAccountIds,
            )
            drafts to result
        }
        return committed.outcome
    }

    /** Rules, then the match window, then the matcher; reads only. */
    private fun reconcile(
        accountId: String,
        candidates: List<Candidate>,
        options: ImportReconcileOptions,
        today: LocalDate,
    ): ImportPlan {
        val offBudget = reads.accountIsOffBudget(accountId)
        // A candidate's rules see the account's rule snapshot, in UTC calendar days.
        val results = RuleEngine(database).run(candidates.map { ImportReconcileProjection.ruleInput(it, accountId) }, today)
        val projection = ImportReconcileProjection.project(candidates, results, accountId, offBudget)
        if (projection.movedSources.isNotEmpty()) {
            throw LocalFirstException.InvalidLocalWrite("a rule moves an imported transaction to another account")
        }
        // The days before rules ran bound the read, so a rule that moves a date cannot hide a row from matching.
        val existing = reads.existingRows(
            accountId,
            ImportDays.window(candidates.map { it.dayId }),
            candidates.mapNotNull { it.financialId }.toSet(),
        )
        val suppressed: Set<String> = when (options.reimportDeleted) {
            true -> emptySet()
            false -> reads.suppressedFinancialIds(accountId, ignoringPreference = true)
            null -> reads.suppressedFinancialIds(accountId)
        }
        val effective = if (options.updateDates == null) options.copy(updateDates = reads.updatesDates(accountId)) else options
        val reconciliation = BankSyncReconciliation.plan(projection.candidates, existing, suppressed, offBudget, effective)
        return plan(candidates.size, projection, reconciliation, existing)
    }

    private fun plan(
        rowCount: Int,
        projection: ImportReconcileProjection.Result,
        reconciliation: BankSyncReconciliation.Plan,
        existing: List<Existing>,
    ): ImportPlan {
        val decisions = ArrayList<ImportRowDecision>()
        val decided = HashSet<Int>()
        var added = 0
        var updated = 0
        var unchanged = 0
        var skipped = 0
        for ((position, entry) in reconciliation.entries.withIndex()) {
            val sourceIndex = projection.sources[reconciliation.sources[position]]
            decided.add(sourceIndex)
            when (entry) {
                is Entry.Insert -> {
                    added++
                    decisions.add(ImportRowDecision(sourceIndex, ImportDecisionKind.ADDED, candidate = entry.candidate))
                }
                is Entry.Update -> {
                    updated++
                    decisions.add(ImportRowDecision(sourceIndex, ImportDecisionKind.UPDATED, entry.update.existingId, entry.update))
                }
                is Entry.Unchanged -> {
                    unchanged++
                    decisions.add(ImportRowDecision(sourceIndex, ImportDecisionKind.UNCHANGED, entry.existingId))
                }
                is Entry.SkippedDeleted -> {
                    skipped++
                    decisions.add(ImportRowDecision(sourceIndex, ImportDecisionKind.SKIPPED_DELETED))
                }
            }
        }
        // Rows a delete-transaction rule dropped without a match have no entry.
        for (index in 0 until rowCount) {
            if (index in decided) continue
            skipped++
            decisions.add(ImportRowDecision(index, ImportDecisionKind.SKIPPED_BY_RULE))
        }
        decisions.sortBy { it.sourceIndex }
        return ImportPlan(decisions, added, updated, unchanged, skipped, reconciliation, existing)
    }
}
