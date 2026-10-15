package com.sporez.actualist.core.banksync

import com.sporez.actualist.core.importer.BankSyncReconciliation.Existing
import com.sporez.actualist.core.importer.BankSyncReconciliation.MatchedUpdate
import com.sporez.actualist.core.importer.ImportDecisionKind
import com.sporez.actualist.core.importer.ImportPlan

/** One matched row a sync updated: what it looks like and which of its fields the download filled. */
data class BankSyncMatchDetail(
    val transactionId: String,
    val title: String,
    /** `YYYYMMDD` of the local row. */
    val dayId: String,
    val amount: Long,
    val changes: List<String>,
)

/** Builds [BankSyncMatchDetail]s from a reconcile plan (Actualist's `BankSyncReview.MatchChange` lines). */
object BankSyncMatchDetails {
    /** The fields [update] fills or changes on [existing], worded as the review sheet words them. */
    fun changes(update: MatchedUpdate, existing: Existing): List<String> {
        val out = ArrayList<String>()
        if (update.financialId != null && update.financialId != existing.financialId) {
            out.add(if (existing.financialId == null) "Attach bank transaction ID" else "Replace existing bank transaction ID")
        }
        if (update.newPayeeName != null) {
            out.add("Payee: None → ${update.newPayeeName} (new payee)")
        } else if (update.payeeId != null && update.payeeId != existing.payeeId) {
            out.add("Payee: ${if (existing.payeeId == null) "None" else "changed"} → set from the bank")
        }
        if (update.dayId != null && update.dayId != existing.dayId) {
            out.add("Date: ${dayText(existing.dayId)} → ${dayText(update.dayId)}")
        }
        if (update.categoryId != null && update.categoryId != existing.categoryId) {
            out.add("Category: ${if (existing.categoryId == null) "Uncategorized" else "changed"} → set by rules")
        }
        if (update.importedPayee != null && update.importedPayee != existing.importedPayee) {
            out.add("Bank payee: ${quoted(existing.importedPayee)} → ${quoted(update.importedPayee)}")
        }
        if (update.notes != null && update.notes != existing.notes) {
            out.add("Notes: ${quoted(existing.notes)} → ${quoted(update.notes)}")
        }
        if (update.cleared != existing.cleared) {
            out.add("Cleared: ${if (existing.cleared) "Yes" else "No"} → ${if (update.cleared) "Yes" else "No"}")
        }
        if (update.childIds.isNotEmpty()) {
            val count = update.childIds.size
            out.add(if (count == 1) "Mark 1 split transaction cleared" else "Mark $count split transactions cleared")
        }
        return out
    }

    /** Details for every UPDATED decision of [plan], in input order. */
    fun from(plan: ImportPlan): List<BankSyncMatchDetail> {
        val existingById = plan.existing.associateBy { it.id }
        val out = ArrayList<BankSyncMatchDetail>()
        for (decision in plan.decisions) {
            if (decision.kind != ImportDecisionKind.UPDATED) continue
            val update = decision.update ?: continue
            val existing = existingById[update.existingId] ?: continue
            val title = existing.importedPayee?.takeIf { it.isNotBlank() }
                ?: update.importedPayee?.takeIf { it.isNotBlank() }
                ?: update.newPayeeName?.takeIf { it.isNotBlank() }
                ?: "Transaction"
            out.add(BankSyncMatchDetail(existing.id, title, existing.dayId, existing.amount, changes(update, existing)))
        }
        return out
    }

    /** `YYYYMMDD` as `YYYY-MM-DD`; anything else is returned unchanged. */
    fun dayText(dayId: String): String =
        if (dayId.length == 8 && dayId.all { it.isDigit() }) "${dayId.substring(0, 4)}-${dayId.substring(4, 6)}-${dayId.substring(6, 8)}" else dayId

    private fun quoted(raw: String?): String = if (raw.isNullOrEmpty()) "None" else "“$raw”"
}

/** Bank Sync wording shared with Actualist's `BankSyncCopy`: per-account status, last-sync age, provider failures. */
object BankSyncCopy {
    /** The short status for a durable `bank_sync_status`, or null for a healthy or unknown-empty one. */
    fun statusText(durableStatus: String?): String? = when (durableStatus) {
        null, "", "ok" -> null
        "attention-required" -> "Needs attention"
        "reauth-required" -> "Reconnect required"
        "rate-limit-exceeded" -> "Rate limited"
        "timed-out" -> "Timed out"
        "account-missing" -> "Account missing at bank"
        else -> "Failed"
    }

    /** True when [durableStatus] is a failure state (the row shows a red status). */
    fun isFailure(durableStatus: String?): Boolean = statusText(durableStatus) != null

    /** "Not linked", "Never synced" or "Synced 2h ago"; [lastSyncMillis] is the account's `last_sync` text. */
    fun lastSyncText(lastSyncMillis: String?, isLinked: Boolean, nowMillis: Long): String {
        if (!isLinked) return "Not linked"
        val millis = lastSyncMillis?.trim()?.toLongOrNull() ?: return "Never synced"
        return "Synced " + ageText(millis, nowMillis)
    }

    /** Compact age: "just now", "5m ago", "2h ago", "3d ago". */
    fun ageText(sinceMillis: Long, nowMillis: Long): String {
        val seconds = (nowMillis - sinceMillis) / 1000
        return when {
            seconds < 60 -> "just now"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86_400}d ago"
        }
    }

    /** Actualist's wording for a provider failure that the generic message would hide, or null to keep the generic one. */
    fun failureText(error: Throwable): String? = when (error) {
        is SimpleFinException.HttpStatus -> when (error.status) {
            402 -> "SimpleFIN says this connection requires payment."
            403 -> "SimpleFIN access was denied. The connection may have been revoked."
            429 -> "Rate limited. Try again later."
            else -> null
        }
        is SimpleFinException.ProviderError -> when (BankSyncStatus.fromErrorCode(error.errorCode)) {
            BankSyncStatus.REAUTH_REQUIRED -> "Reconnect required. SimpleFIN access was denied. The connection may have been revoked."
            BankSyncStatus.RATE_LIMIT_EXCEEDED -> "Rate limited. Try again later."
            else -> null
        }
        else -> null
    }

    /** The explanation under the account sheet's actions. */
    fun linkedFooter(isSimpleFin: Boolean): String =
        if (isSimpleFin) {
            "Unlinking leaves this account's transactions in place and only clears the bank connection."
        } else {
            "This account is linked through another provider. SimpleFIN Bank Sync cannot change that connection."
        }

    /** Where another provider's links are removed. */
    fun otherProviderUnlink(provider: String): String = "Unlink $provider in a supported client first."

    /** "2× Invalid amount · 1× Missing date" from problem messages, sorted by message. */
    fun problemSummary(messages: List<String>): String? {
        if (messages.isEmpty()) return null
        return messages.groupingBy { it }.eachCount().toSortedMap().entries.joinToString(" · ") { "${it.value}× ${it.key}" }
    }
}
