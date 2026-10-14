package com.sporez.actualist.core.banksync

import com.sporez.actualist.core.importer.BankSyncReconciliation
import com.sporez.actualist.core.importer.ImportDays
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.LocalDate

/** The budget's currency scale. Port of Actualist `BudgetCurrency`: amounts scale by its decimal places, never times 100. */
data class BudgetCurrency(val code: String, val decimalPlaces: Int) {
    /**
     * A currency-neutral budget (empty code) accepts any code of the same scale; an explicit code needs an
     * exact match. A blank incoming code is never accepted.
     */
    fun accepts(otherCode: String): Boolean {
        val incoming = otherCode.trim().uppercase()
        if (incoming.isEmpty()) return false
        if (code.isEmpty()) return catalog(incoming).decimalPlaces == decimalPlaces
        return incoming == code.uppercase()
    }

    /** Display units to integer minor units, rounding half away from zero; null when it does not fit a Long. */
    fun minorUnits(display: BigDecimal): Long? = try {
        display.movePointRight(decimalPlaces).setScale(0, RoundingMode.HALF_UP).longValueExact()
    } catch (error: ArithmeticException) {
        null
    }

    companion object {
        val NONE = BudgetCurrency("", 2)
        private val ZERO_DECIMAL = setOf("JPY", "KRW")

        fun catalog(code: String): BudgetCurrency {
            val resolved = code.trim().uppercase()
            return BudgetCurrency(resolved, if (resolved in ZERO_DECIMAL) 0 else 2)
        }
    }
}

/** Why a downloaded row cannot be imported safely. Any problem aborts the account's write. */
data class BankSyncProblem(val remoteTransactionId: String?, val message: String) {
    companion object {
        fun missingMappingSide(remoteTransactionId: String?, isPayment: Boolean) = BankSyncProblem(
            remoteTransactionId,
            if (isPayment) "Custom mapping has no payment side" else "Custom mapping has no deposit side",
        )

        val INVALID_CUSTOM_MAPPING = BankSyncProblem(null, "Invalid custom sync mapping")
    }
}

/** Durable `accounts.bank_sync_status` vocabulary. Unknown provider codes are recorded as `failed`. */
enum class BankSyncStatus(val raw: String) {
    OK("ok"),
    ATTENTION_REQUIRED("attention-required"),
    REAUTH_REQUIRED("reauth-required"),
    RATE_LIMIT_EXCEEDED("rate-limit-exceeded"),
    TIMED_OUT("timed-out"),
    ACCOUNT_MISSING("account-missing"),
    FAILED("failed"),
    ;

    companion object {
        /** A null code is a successful download. */
        fun fromErrorCode(errorCode: String?): BankSyncStatus {
            if (errorCode == null) return OK
            return when (errorCode.uppercase()) {
                "TIMED_OUT" -> TIMED_OUT
                "ACCOUNT_MISSING" -> ACCOUNT_MISSING
                "ACCOUNT_NEEDS_ATTENTION" -> ATTENTION_REQUIRED
                "RATE_LIMIT_EXCEEDED" -> RATE_LIMIT_EXCEEDED
                "INVALID_ACCESS_TOKEN", "UNAUTHORIZED", "INVALID_CREDENTIALS" -> REAUTH_REQUIRED
                else -> FAILED
            }
        }
    }
}

/** Currency-safe conversions for bank-sync downloads. Port of Actualist `BankSyncAmounts`. */
object BankSyncAmounts {
    fun normalizedCurrencyCode(raw: String?): String? = raw?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

    /**
     * Parses a raw decimal string into minor units at the budget currency's scale. Only an optional sign,
     * ASCII digits and at most one `.` are accepted, with at least one digit before any separator; junk is
     * never guessed into an amount.
     */
    fun minorUnits(raw: String?, currency: BudgetCurrency): Long? {
        val trimmed = raw?.trim(' ', '\t') ?: return null
        if (trimmed.isEmpty()) return null
        var body = trimmed
        var sign = 1L
        if (body.startsWith("-")) {
            sign = -1L
            body = body.substring(1)
        } else if (body.startsWith("+")) {
            body = body.substring(1)
        }
        if (body.isEmpty()) return null
        var seenSeparator = false
        for (character in body) {
            if (character == '.') {
                if (seenSeparator) return null
                seenSeparator = true
            } else if (character !in '0'..'9') {
                return null
            }
        }
        if (body.none { it in '0'..'9' } || body.startsWith(".")) return null
        val magnitude = currency.minorUnits(BigDecimal(body)) ?: return null
        return sign * magnitude
    }

    /** UTC calendar day (`YYYYMMDD`) of UNIX seconds; null when the instant is out of range. */
    fun dayId(unixSeconds: Long): String? = try {
        SimpleFinDays.dayId(unixSeconds)
    } catch (error: DateTimeException) {
        null
    }

    /**
     * Sync lookback start in `YYYY-MM-DD`: the later of today minus 89 days and the oldest live transaction
     * (loot-core `getAccountSyncStartDate`), so an empty account asks for the full 90 days.
     */
    fun lookbackStartDate(oldestLiveTransactionDayId: String?, today: LocalDate): String {
        val earliestAllowed = today.minusDays(89)
        val oldest = oldestLiveTransactionDayId?.let(ImportDays::date)
        val earliest = if (oldest != null && oldest.isAfter(earliestAllowed)) oldest else earliestAllowed
        return earliest.toString()
    }
}

/** The explicit account-balance effect of a sync. */
sealed class BalanceDisposition {
    data class Known(val minorUnits: Long) : BalanceDisposition()
    data object Clear : BalanceDisposition()
    data object Preserve : BalanceDisposition()
}

/** Pure conversion from provider evidence to the balance effect. Port of Actualist `BankSyncBalancePlanning`. */
object BankSyncBalancePlanning {
    fun disposition(
        download: SimpleFinAccountDownload,
        fallbackRemote: SimpleFinRemoteAccount?,
        currency: BudgetCurrency,
    ): BalanceDisposition {
        if (download.hasError) return BalanceDisposition.Preserve
        val evidence = download.currentBalance
            ?: fallbackRemote?.balance?.let { SimpleFinBalanceAmount(it, fallbackRemote.currency) }
        if (evidence != null) {
            val balanceCurrency = BankSyncAmounts.normalizedCurrencyCode(evidence.currency)
            if (balanceCurrency == null || !currency.accepts(balanceCurrency)) return BalanceDisposition.Clear
            val minor = BankSyncAmounts.minorUnits(evidence.amount, currency) ?: return BalanceDisposition.Clear
            return BalanceDisposition.Known(minor)
        }
        val starting = download.startingBalance
        if (currency.decimalPlaces == 2 && starting != null) return BalanceDisposition.Known(starting)
        return BalanceDisposition.Clear
    }
}

/** Per-account import preferences (`sync-import-pending-*`, `sync-import-notes-*`, `custom-sync-mappings-*`). */
data class BankSyncImportPreferences(
    val importPending: Boolean = true,
    val importNotes: Boolean = true,
    val customMappingsJson: String? = null,
)

/** Actual `custom-sync-mapping.ts` for Bank Sync downloads. A parse failure is a configuration error, not a default. */
object BankSyncFieldMapping {
    class ParseException : Exception("invalid custom sync mapping")

    data class Mappings(val payment: Map<String, String>?, val deposit: Map<String, String>?) {
        fun side(amountMinorUnits: Long): Map<String, String>? = if (amountMinorUnits <= 0L) payment else deposit
    }

    data class Resolved(val dateUnixSeconds: Long?, val payeeName: String?, val notes: String?)

    val DEFAULTS = Mappings(
        payment = mapOf("date" to "date", "payee" to "payeeName", "notes" to "notes"),
        deposit = mapOf("date" to "date", "payee" to "payeeName", "notes" to "notes"),
    )

    /** Invalid JSON, a non-object root or side, and a non-string field value all fail closed. */
    fun parse(text: String): Mappings {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject ?: throw ParseException()
        } catch (error: ParseException) {
            throw error
        } catch (error: Exception) {
            throw ParseException()
        }
        fun side(key: String): Map<String, String>? {
            val raw = root[key] ?: return null
            val inner = raw as? JsonObject ?: throw ParseException()
            val mapped = LinkedHashMap<String, String>()
            for ((field, value) in inner) {
                val primitive = value as? JsonPrimitive
                if (primitive == null || value is JsonNull || !primitive.isString) throw ParseException()
                mapped[field] = primitive.content
            }
            return mapped
        }
        return Mappings(side("payment"), side("deposit"))
    }

    /**
     * loot-core `normalizeBankSyncTransactions` mapping: `date` and `payee` fall back to the canonical
     * fields, `notes` does not. Hash escaping applies only to imported provider notes. Null when the amount's
     * side has no mapping.
     */
    fun resolve(transaction: SimpleFinRemoteTransaction, amountMinorUnits: Long, mappings: Mappings, importNotes: Boolean): Resolved? {
        val side = mappings.side(amountMinorUnits) ?: return null
        val raw = LinkedHashMap(transaction.rawFields.values)
        if (raw["date"] == null) transaction.dateUnixSeconds?.let { raw["date"] = RawValue.Integer(it) }
        if (raw["payeeName"] == null) transaction.payeeName?.let { raw["payeeName"] = RawValue.Text(it) }
        if (raw["notes"] == null) transaction.notes?.let { raw["notes"] = RawValue.Text(it) }

        val date = side["date"]?.let { raw[it]?.unixSeconds } ?: transaction.dateUnixSeconds
        val payee = side["payee"]?.let { raw[it]?.scalarString } ?: transaction.payeeName
        val mappedNotes = side["notes"]?.let { raw[it]?.scalarString }
        val notes = if (importNotes && mappedNotes != null) {
            BankSyncReconciliation.escapedNotes(mappedNotes).takeIf { it.isNotEmpty() }
        } else {
            null
        }
        return Resolved(date, payee, notes)
    }
}
