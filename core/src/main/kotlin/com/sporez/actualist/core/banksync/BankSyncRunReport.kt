package com.sporez.actualist.core.banksync

/** One account's line in a multi-account bank sync run. */
data class BankSyncRunLine(val accountId: String, val accountName: String, val text: String, val failed: Boolean)

/** Builds the per-account result lines and the one-line outcome of a "Sync All" run. */
object BankSyncRunReport {
    /** The line for an account whose sync finished; a provider-level skip counts as failed. */
    fun line(accountId: String, accountName: String, summary: SimpleFinSyncSummary): BankSyncRunLine {
        val result = summary.accounts.firstOrNull { it.localAccountId == accountId }
        if (result != null && result.skipped) {
            val reason = result.errorCode ?: result.status.raw
            return BankSyncRunLine(accountId, accountName, "Skipped: $reason", true)
        }
        return BankSyncRunLine(accountId, accountName, summary.text(), false)
    }

    /** The line for an account whose sync threw. */
    fun failure(accountId: String, accountName: String, message: String?): BankSyncRunLine =
        BankSyncRunLine(accountId, accountName, message?.takeIf { it.isNotBlank() } ?: "Bank sync failed.", true)

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
