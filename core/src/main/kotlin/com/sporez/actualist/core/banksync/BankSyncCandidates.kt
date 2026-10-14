package com.sporez.actualist.core.banksync

import com.sporez.actualist.core.importer.BankSyncReconciliation.Candidate

/**
 * Provider rows to import candidates (Actualist `prepareDownload`). Pure: payee ids are resolved against the
 * lowercased-name lookup the caller hands in; rules, matching and writes happen in the shared reconciler.
 */
object BankSyncCandidates {
    class Result(val candidates: List<Candidate>, val problems: List<BankSyncProblem>)

    fun prepare(
        download: SimpleFinAccountDownload,
        preferences: BankSyncImportPreferences,
        currency: BudgetCurrency,
        payeeIdsByLowerName: Map<String, String>,
    ): Result {
        val problems = ArrayList<BankSyncProblem>()
        val mappings = try {
            preferences.customMappingsJson?.let(BankSyncFieldMapping::parse) ?: BankSyncFieldMapping.DEFAULTS
        } catch (error: BankSyncFieldMapping.ParseException) {
            problems.add(BankSyncProblem.INVALID_CUSTOM_MAPPING)
            return Result(emptyList(), problems)
        }
        val candidates = ArrayList<Candidate>()
        for (transaction in download.transactions) {
            // Actual skips uncleared downloads when import-pending is off, before rules, matching or balance math.
            if (!preferences.importPending && transaction.booked != true) continue
            val problemId = transaction.id
            if (problemId == null || problemId.trim().isEmpty()) {
                problems.add(BankSyncProblem(null, "Missing transaction ID"))
                continue
            }
            val transactionCurrency = BankSyncAmounts.normalizedCurrencyCode(transaction.currency)
            if (transactionCurrency == null) {
                problems.add(BankSyncProblem(problemId, "Missing transaction currency"))
                continue
            }
            if (!currency.accepts(transactionCurrency)) {
                val budgetLabel = if (currency.code.isEmpty()) "none" else currency.code.uppercase()
                problems.add(BankSyncProblem(problemId, "Currency mismatch ($transactionCurrency bank transaction, $budgetLabel budget)"))
                continue
            }
            val amount = BankSyncAmounts.minorUnits(transaction.amount, currency)
            if (amount == null) {
                problems.add(BankSyncProblem(problemId, "Unreadable amount"))
                continue
            }
            val mapped = BankSyncFieldMapping.resolve(transaction, amount, mappings, preferences.importNotes)
            if (mapped == null) {
                problems.add(BankSyncProblem.missingMappingSide(problemId, amount <= 0L))
                continue
            }
            val seconds = mapped.dateUnixSeconds
            val dayId = if (seconds == null) null else BankSyncAmounts.dayId(seconds)
            if (dayId == null) {
                problems.add(BankSyncProblem(problemId, "Unreadable date"))
                continue
            }
            val payeeName = mapped.payeeName?.trim()
            if (payeeName.isNullOrEmpty()) {
                problems.add(BankSyncProblem(problemId, "Missing payee"))
                continue
            }
            candidates.add(
                Candidate(
                    financialId = problemId,
                    dayId = dayId,
                    amount = amount,
                    payeeId = payeeIdsByLowerName[payeeName.lowercase()],
                    payeeName = payeeName,
                    notes = mapped.notes,
                    categoryId = null,
                    // Actual core uses Boolean(trans.booked): an absent or unreadable state stays pending.
                    cleared = transaction.booked == true,
                    importedPayee = payeeName,
                ),
            )
        }
        return Result(candidates, problems)
    }
}
