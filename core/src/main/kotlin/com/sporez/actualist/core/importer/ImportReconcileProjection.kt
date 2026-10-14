package com.sporez.actualist.core.importer

import com.sporez.actualist.core.importer.BankSyncReconciliation.Candidate
import com.sporez.actualist.core.rules.RuleRunResult
import com.sporez.actualist.core.rules.RuleTransactionInput
import java.time.LocalDate

/**
 * The rule step every import shares: build the transaction the rules evaluate, then project each candidate
 * through its rule result before matching (loot-core runs `runRules` on `transactionsStep1`, ahead of any
 * match). Pure; the caller owns the batched `RuleEngine.run` call. Port of Actualist `ImportReconcileProjection`.
 */
object ImportReconcileProjection {
    class Result(
        /** Candidates that survive their rules, in input order. */
        val candidates: List<Candidate>,
        /** The input index of each surviving candidate. */
        val sources: List<Int>,
        /**
         * Input indices whose rule moves the row to another account. An import never writes into an account
         * other than its own, so the caller refuses them rather than dropping them silently.
         */
        val movedSources: List<Int>,
    )

    /** The day the rules start from. A malformed day id falls back to 1970-01-01, as Actualist does. */
    fun draftDate(candidate: Candidate): LocalDate = ImportDays.date(candidate.dayId) ?: LocalDate.ofEpochDay(0)

    /**
     * The input rules see. A new payee has no id yet, so the raw name rides along as the payee name
     * and the imported payee (rules can match both).
     */
    fun ruleInput(candidate: Candidate, accountId: String): RuleTransactionInput = RuleTransactionInput(
        accountId = accountId,
        date = draftDate(candidate),
        amount = candidate.amount,
        payeeId = candidate.payeeId,
        payeeName = candidate.payeeName ?: "",
        categoryId = candidate.categoryId,
        notes = candidate.notes,
        cleared = candidate.cleared,
        isTransfer = false,
        importedPayee = candidate.importedPayee,
    )

    /** [results] holds one rule result per candidate, in order. */
    fun project(
        candidates: List<Candidate>,
        results: List<RuleRunResult>,
        accountId: String,
        accountIsOffBudget: Boolean,
    ): Result {
        val projected = ArrayList<Candidate>(candidates.size)
        val sources = ArrayList<Int>(candidates.size)
        val moved = ArrayList<Int>()
        for (source in candidates.indices) {
            if (source >= results.size) break
            val candidate = candidates[source]
            val result = results[source]
            if (result.accountId != accountId) {
                moved.add(source)
                continue
            }
            projected.add(BankSyncReconciliation.applyingRule(result, candidate, draftDate(candidate), accountIsOffBudget))
            sources.add(source)
        }
        return Result(projected, sources, moved)
    }
}
