package com.sporez.actualist.core.importer

import com.sporez.actualist.core.rules.RuleRunResult
import java.time.LocalDate

/** Distinguishes an omitted key from an explicit null, like JavaScript `'field' in data` in Actual's `makeChild`. */
sealed class SplitField<out T> {
    object Omitted : SplitField<Nothing>()
    data class Value<out T>(val value: T?) : SplitField<T>()
}

/**
 * Pure port of loot-core's bank-sync matcher (`sync.ts` `matchTransactions` plus the update stage of
 * `reconcileTransactions`), shared by Bank Sync and CSV import. Port of Actualist `BankSyncReconciliation`.
 *
 * No I/O: callers hand in the normalized, rule-projected candidates and the account's live local rows.
 * `existing` carries the account's live rows in the plus or minus 7 day window (`v_transactions` semantics:
 * valid split children included, tombstones and invalid `is_child` rows without `parent_id` excluded by the
 * caller). Children referenced by a parent cascade are looked up inside this list.
 */
object BankSyncReconciliation {
    /** One downloaded or imported transaction after normalization and rule projection. */
    data class Candidate(
        /** Bank's transaction id, destined for `financial_id`. Null when the source had none. */
        val financialId: String?,
        /** Calendar day, `YYYYMMDD`. */
        val dayId: String,
        val amount: Long,
        /** Payee resolved by the caller against existing payees by name, case-insensitive, before matching. */
        val payeeId: String?,
        /** Raw payee name, so an insert can resolve-or-create the payee at apply time. */
        val payeeName: String?,
        val notes: String?,
        val categoryId: String?,
        val cleared: Boolean,
        val importedPayee: String?,
        val splits: List<Split> = emptyList(),
        val scheduleId: String? = null,
        /**
         * False when the source never said whether the row is cleared (a CSV without a Cleared column).
         * Matching treats it as not cleared, and an insert takes `defaultCleared` instead.
         */
        val clearedIsExplicit: Boolean = true,
        /** A delete-transaction rule matched: the row still matches and updates, but is never inserted. */
        val deletedByRule: Boolean = false,
    ) {
        val isSplit: Boolean get() = splits.isNotEmpty()
    }

    data class Split(
        val categoryId: String?,
        val amount: Long,
        val payeeId: SplitField<String> = SplitField.Omitted,
        val notes: SplitField<String> = SplitField.Omitted,
        val sortOrder: SplitField<Double> = SplitField.Omitted,
    )

    /** One live local transaction row eligible for matching. */
    data class Existing(
        val id: String,
        val financialId: String?,
        /** `YYYYMMDD`. */
        val dayId: String,
        val amount: Long,
        val payeeId: String?,
        val categoryId: String?,
        val notes: String?,
        val cleared: Boolean,
        /** Reconciled rows are locked: they match but never update and never cascade. */
        val reconciled: Boolean,
        val importedPayee: String?,
        val isParent: Boolean,
        val isChild: Boolean,
        val parentId: String?,
        /** Paired transfer row id (`transferred_id`). */
        val transferId: String?,
    ) {
        val isTransfer: Boolean get() = !transferId.isNullOrEmpty()

        /** `v_transactions` validity: children must carry a parent id. */
        val isValidCandidate: Boolean get() = !isChild || parentId != null
    }

    /**
     * The write planned onto one matched local row. Blank local fields are filled from the download;
     * user-filled payee, category and notes win. [financialId] and [importedPayee] are the row's own
     * import identity: null means the source had none.
     */
    data class MatchedUpdate(
        val existingId: String,
        val financialId: String?,
        /** A transfer payee on a plain row converts it into a transfer; the writer creates the paired leg. */
        val payeeId: String?,
        val categoryId: String?,
        val importedPayee: String?,
        val notes: String?,
        val cleared: Boolean,
        /** Live split children of a matched split parent that receive the same cleared value. */
        val childIds: List<String>,
        /** The download's day, when `updateDates` moves a matched row. Null leaves the date alone. */
        val dayId: String? = null,
        /** Live children of a matched split parent that move to [dayId] with it. */
        val dateChildIds: List<String> = emptyList(),
        /** Download payee name with no payee yet; the writer finds or creates it and sets it as [payeeId]. */
        val newPayeeName: String? = null,
    )

    sealed class Entry {
        /** No local row matched; the candidate inserts as a new transaction (with split children when split). */
        data class Insert(val candidate: Candidate) : Entry()
        data class Update(val update: MatchedUpdate) : Entry()

        /** Matched but either reconciled (locked) or already identical. */
        data class Unchanged(val existingId: String) : Entry()

        /** Exact bank id was previously deleted; no local row may be written. */
        data class SkippedDeleted(val financialId: String) : Entry()
    }

    data class Plan(
        val entries: List<Entry>,
        /** For each entry, the index in the `candidates` array it came from. */
        val sources: List<Int>,
    ) {
        val inserts: List<Candidate> get() = entries.filterIsInstance<Entry.Insert>().map { it.candidate }
    }

    private class StepOne(
        val source: Int,
        val candidate: Candidate,
        val matchedId: String?,
        val fuzzy: List<Existing>?,
    )

    /**
     * loot-core three-pass match, in order: (1) `financial_id` equality, (2) same payee within 7 days and the
     * same amount across every candidate, (3) nearest remaining same-amount row in the window. A local row is
     * claimed by at most one download. Children of a split parent matched by `financial_id` are reserved from
     * both fuzzy passes for the whole batch, regardless of download order (loot-core 24deae7).
     */
    fun plan(
        candidates: List<Candidate>,
        existing: List<Existing>,
        suppressedFinancialIds: Set<String> = emptySet(),
        accountIsOffBudget: Boolean = false,
        options: ImportReconcileOptions = ImportReconcileOptions.BANK_SYNC,
    ): Plan {
        val epochDays: List<Long?> = existing.map { ImportDays.epochDay(it.dayId) }
        // The fuzzy query only looks at rows of the candidate's amount, so index them once.
        val offsetsByAmount: Map<Long, List<Int>> = existing.indices.groupBy { existing[it].amount }
        val claimed = HashSet<String>()
        val exactMatchedParentIds = HashSet<String>()

        val stepOne = ArrayList<StepOne>()
        val entries = ArrayList<Entry>()
        val sources = ArrayList<Int>()
        for ((source, candidate) in candidates.withIndex()) {
            var idMatch: Existing? = null
            val financialId = candidate.financialId
            if (!financialId.isNullOrEmpty()) {
                idMatch = existing.firstOrNull { it.isValidCandidate && it.financialId == financialId }
                if (idMatch != null) {
                    claimed.add(idMatch.id)
                    if (idMatch.isParent) exactMatchedParentIds.add(idMatch.id)
                } else if (financialId in suppressedFinancialIds) {
                    entries.add(Entry.SkippedDeleted(financialId))
                    sources.add(source)
                    continue
                }
            }
            val fuzzy: List<Existing>? = if (idMatch == null) {
                fuzzyDataset(candidate, existing, epochDays, options.strictIdChecking, offsetsByAmount)
            } else {
                null
            }
            stepOne.add(StepOne(source, candidate, idMatch?.id, fuzzy))
        }

        fun isReserved(row: Existing): Boolean = row.parentId?.let { it in exactMatchedParentIds } ?: false

        // Pass 2: same payee (loot-core transactionsStep2).
        val matches = HashMap<Int, String>()
        for ((index, step) in stepOne.withIndex()) {
            if (step.matchedId != null) continue
            val fuzzy = step.fuzzy ?: continue
            val payeeId = step.candidate.payeeId ?: continue
            val row = fuzzy.firstOrNull { it.id !in claimed && !isReserved(it) && it.payeeId == payeeId } ?: continue
            claimed.add(row.id)
            matches[index] = row.id
        }

        // Pass 3: nearest remaining same-amount row (transactionsStep3).
        for ((index, step) in stepOne.withIndex()) {
            if (matches[index] != null || step.matchedId != null) continue
            val fuzzy = step.fuzzy ?: continue
            val row = fuzzy.firstOrNull { it.id !in claimed && !isReserved(it) }
            if (row != null) {
                claimed.add(row.id)
                matches[index] = row.id
            }
        }

        val existingById = HashMap<String, Existing>()
        for (row in existing) existingById[row.id] = row
        for ((index, step) in stepOne.withIndex()) {
            val candidate = step.candidate
            val matchId = step.matchedId ?: matches[index]
            val row = matchId?.let { existingById[it] }
            if (row == null) {
                // A rule-deleted download that matched nothing is not inserted.
                if (candidate.deletedByRule) continue
                entries.add(Entry.Insert(if (accountIsOffBudget) withoutBudgetCategory(candidate) else candidate))
                sources.add(step.source)
                continue
            }

            // Reconciled rows are locked: matched but never written.
            if (row.reconciled) {
                entries.add(Entry.Unchanged(row.id))
                sources.add(step.source)
                continue
            }

            val existingNotes = row.notes?.takeIf { it.isNotEmpty() }
            val payeeId = mergedPayeeId(row, candidate)
            val newPayeeName = if (payeeId == null && !row.isTransfer) {
                candidate.payeeName?.takeIf { it.trim().isNotEmpty() }
            } else {
                null
            }
            val movedDayId = if (options.updateDates == true && candidate.dayId != row.dayId) candidate.dayId else null
            val cleared = row.cleared || candidate.cleared
            val update = MatchedUpdate(
                existingId = row.id,
                financialId = candidate.financialId,
                payeeId = payeeId,
                categoryId = mergedCategoryId(row, candidate, accountIsOffBudget),
                // `imported_payee: trans.imported_payee || null`: '' updates as null.
                importedPayee = candidate.importedPayee?.takeIf { it.isNotEmpty() },
                // `notes: existing.notes || trans.notes || null`: a rule's '' writes nothing.
                notes = existingNotes ?: candidate.notes?.takeIf { it.isNotEmpty() },
                cleared = cleared,
                childIds = childIdsForClearCascade(row, existing, cleared),
                dayId = movedDayId,
                dateChildIds = movedDayId?.let { childIdsForDateCascade(row, existing, it) } ?: emptyList(),
                newPayeeName = newPayeeName,
            )
            // loot-core hasFieldsChanged: a match that would write nothing is reported as unchanged.
            if (update.financialId == row.financialId &&
                update.payeeId == row.payeeId &&
                update.categoryId == row.categoryId &&
                update.importedPayee == row.importedPayee &&
                update.notes == existingNotes &&
                update.cleared == row.cleared &&
                update.dayId == null &&
                update.newPayeeName == null
            ) {
                entries.add(Entry.Unchanged(row.id))
            } else {
                entries.add(Entry.Update(update))
            }
            sources.add(step.source)
        }
        return Plan(entries, sources)
    }

    /**
     * loot-core fuzzy query: same amount, date within plus or minus 7 calendar days inclusive, sorted by day
     * distance (stable). Without `strictIdChecking`, rows with a different or absent `financial_id` are still
     * eligible; with it, a row that already has an id is skipped when the candidate has one too.
     */
    fun fuzzyDataset(
        candidate: Candidate,
        existing: List<Existing>,
        epochDays: List<Long?>,
        strictIdChecking: Boolean = false,
        offsetsByAmount: Map<Long, List<Int>>? = null,
    ): List<Existing> {
        // A malformed day never falls inside the window.
        val candidateDay = ImportDays.epochDay(candidate.dayId) ?: return emptyList()
        val matches = ArrayList<Pair<Long, Int>>()
        val offsets: List<Int> = if (offsetsByAmount != null) offsetsByAmount[candidate.amount] ?: emptyList() else existing.indices.toList()
        for (offset in offsets) {
            val row = existing[offset]
            if (!row.isValidCandidate || row.amount != candidate.amount) continue
            val day = epochDays[offset] ?: continue
            if (strictIdChecking && !candidate.financialId.isNullOrEmpty() && row.financialId != null) continue
            val distance = Math.abs(day - candidateDay)
            if (distance <= 7) matches.add(distance to offset)
        }
        val sorted = matches.sortedWith(compareBy<Pair<Long, Int>>({ it.first }, { it.second }))
        return sorted.map { existing[it.second] }
    }

    /** Split-parent cleared cascade: the same cleared value lands on its live children whose value differs. */
    private fun childIdsForClearCascade(row: Existing, existing: List<Existing>, cleared: Boolean): List<String> {
        if (!row.isParent || row.cleared == cleared) return emptyList()
        return existing.filter { it.isChild && it.parentId == row.id && it.cleared != cleared }.map { it.id }
    }

    /** Date cascade: a matched parent that moves takes its live children along. */
    private fun childIdsForDateCascade(row: Existing, existing: List<Existing>, dayId: String): List<String> {
        if (!row.isParent) return emptyList()
        return existing.filter { it.isChild && it.parentId == row.id && it.dayId != dayId }.map { it.id }
    }

    /** `existing.payee || trans.payee`. Existing transfers keep their payee. */
    private fun mergedPayeeId(row: Existing, candidate: Candidate): String? =
        if (row.isTransfer) row.payeeId else row.payeeId ?: candidate.payeeId

    /**
     * Transfers keep their existing category, including null. Off-budget accounts never take a budget category.
     * Split parents stay uncategorized in the effective view.
     */
    private fun mergedCategoryId(row: Existing, candidate: Candidate, accountIsOffBudget: Boolean): String? {
        if (row.isTransfer || accountIsOffBudget) return row.categoryId
        if (row.isParent) return null
        return row.categoryId ?: candidate.categoryId
    }

    /** Actual clears category on every off-budget insert, including split children. */
    private fun withoutBudgetCategory(candidate: Candidate): Candidate =
        candidate.copy(categoryId = null, splits = candidate.splits.map { it.copy(categoryId = null) })

    // region Rule projection

    /**
     * Projects one candidate through the rules' result *before* matching (loot-core runs rules on
     * `transactionsStep1`). A delete-transaction rule marks the candidate [Candidate.deletedByRule]. Rule-driven
     * splits turn the candidate into a split parent. [draftDate] is the date the rules started from.
     */
    fun applyingRule(
        result: RuleRunResult,
        candidate: Candidate,
        draftDate: LocalDate,
        accountIsOffBudget: Boolean = false,
    ): Candidate {
        var dayId = candidate.dayId
        if (result.date != draftDate) dayId = ImportDays.dayId(result.date)
        val hasSplits = result.splits.isNotEmpty()
        var cleared = candidate.cleared
        var clearedIsExplicit = candidate.clearedIsExplicit
        if (result.cleared != candidate.cleared) {
            cleared = result.cleared
            clearedIsExplicit = true
        }
        val projected = candidate.copy(
            deletedByRule = result.deletesTransaction,
            // The rules' payee is final: null clears it, an unchanged payee is the candidate's own.
            payeeId = result.payeeId,
            amount = result.amount,
            dayId = dayId,
            // The result carries the final notes value, including null when a rule removes downloaded notes.
            notes = result.notes,
            categoryId = if (hasSplits) null else (result.categoryId ?: candidate.categoryId),
            cleared = cleared,
            clearedIsExplicit = clearedIsExplicit,
            scheduleId = result.scheduleId ?: candidate.scheduleId,
            splits = if (hasSplits) {
                result.splits.map {
                    Split(it.categoryId, it.amount, SplitField.Value(it.payeeId), SplitField.Value(it.notes), SplitField.Value(it.sortOrder))
                }
            } else {
                candidate.splits
            },
        )
        return if (accountIsOffBudget) withoutBudgetCategory(projected) else projected
    }

    /**
     * loot-core `normalizeBankSyncTransactions`: imported notes are trimmed and every `#` is doubled so
     * note-authored markers (`#template`, `#goal`, `#cleanup`) stay inert.
     */
    fun escapedNotes(notes: String): String = notes.trim().replace("#", "##")

    // endregion

    // region Opening balance

    data class OpeningBalance(val amount: Long, val dayId: String)

    /**
     * First-apply balance subtracts only final planned inserts. Rule-suppressed and deleted downloads never
     * affect the resulting account balance; a split contributes its parent amount once.
     */
    fun openingBalance(
        currentBalance: Long,
        inserts: List<Candidate>,
        earliestDayId: String?,
        accountHadLiveTransactions: Boolean,
        todayDayId: String,
    ): OpeningBalance? {
        if (accountHadLiveTransactions) return null
        val amount = currentBalance - inserts.sumOf { it.amount }
        if (amount == 0L) return null
        return OpeningBalance(amount, earliestDayId ?: todayDayId)
    }

    // endregion
}
