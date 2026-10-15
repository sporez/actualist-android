package com.sporez.actualist.core.banksync

/** One account's line in a multi-account bank sync run. */
data class BankSyncRunLine(
    val accountId: String,
    val accountName: String,
    val text: String,
    val failed: Boolean,
    /** New transactions added. */
    val addedCount: Int = 0,
    /** Matched rows that were updated. */
    val updatedCount: Int = 0,
    /** Matched rows left as they were. */
    val unchangedCount: Int = 0,
    /** Rows the sync could not read safely (each message once per row); nothing was saved for a run that has any. */
    val problems: List<String> = emptyList(),
    val openingBalanceAdded: Boolean = false,
    val matchDetails: List<BankSyncMatchDetail> = emptyList(),
    /** Actualist's status wording when the account's download failed, or null. */
    val statusText: String? = null,
) {
    val matchedCount: Int get() = updatedCount + unchangedCount
}

/** Builds the per-account result lines and the one-line outcome of a "Sync All" run. */
object BankSyncRunReport {
    /** The line for an account whose sync finished; a provider-level skip counts as failed. */
    fun line(accountId: String, accountName: String, summary: SimpleFinSyncSummary): BankSyncRunLine {
        val result = summary.accounts.firstOrNull { it.localAccountId == accountId }
        if (result != null && result.skipped) {
            val reason = result.errorCode ?: result.status.raw
            return BankSyncRunLine(accountId, accountName, "Skipped: $reason", true, statusText = BankSyncCopy.statusText(result.status.raw))
        }
        return BankSyncRunLine(
            accountId,
            accountName,
            summary.text(),
            false,
            addedCount = result?.addedCount ?: 0,
            updatedCount = result?.updatedCount ?: 0,
            unchangedCount = result?.unchangedCount ?: 0,
            openingBalanceAdded = result?.openingBalanceAdded ?: false,
            matchDetails = result?.matchDetails.orEmpty(),
        )
    }

    /** The line for an account whose sync threw. */
    fun failure(accountId: String, accountName: String, message: String?): BankSyncRunLine =
        BankSyncRunLine(accountId, accountName, message?.takeIf { it.isNotBlank() } ?: "Bank sync failed.", true)

    /** The line for an account whose sync threw [error]: Actualist's provider wording, and unreadable rows as problems. */
    fun failureOf(accountId: String, accountName: String, error: Throwable): BankSyncRunLine {
        val problems = (error as? SimpleFinException.UnresolvedProblems)?.problems?.map { it.message }.orEmpty()
        val text = BankSyncCopy.failureText(error) ?: error.message
        return failure(accountId, accountName, text).copy(problems = problems)
    }

    /** The persisted "Last result" outcome. */
    fun outcome(lines: List<BankSyncRunLine>): String {
        if (lines.isEmpty()) return "No linked accounts to sync."
        val failed = lines.count { it.failed }
        return when {
            failed == 0 -> if (lines.size == 1) "Synced 1 account." else "Synced ${lines.size} accounts."
            failed == lines.size -> if (lines.size == 1) "1 account failed." else "All ${lines.size} accounts failed."
            else -> "Synced ${lines.size - failed} of ${lines.size} accounts, $failed failed."
        }
    }
}
