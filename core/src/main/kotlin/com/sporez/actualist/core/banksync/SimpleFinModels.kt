package com.sporez.actualist.core.banksync

import com.sporez.actualist.core.importer.ImportDays
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Typed failures of SimpleFIN bank sync. Messages never contain tokens or credentials. */
sealed class SimpleFinException(message: String) : Exception(message) {
    class InvalidUrl : SimpleFinException("The server URL is invalid.")
    class InsecureUrl : SimpleFinException("Use an https:// server URL. Plain http is only allowed for this device itself.")
    class Unreachable(cause: java.io.IOException) : SimpleFinException("Could not reach the server. Check the URL and your connection.") {
        init {
            initCause(cause)
        }
    }

    /** True when the connection timed out, which Actualist records as the `timed-out` status. */
    class TimedOut(cause: java.io.IOException) : SimpleFinException("The server took too long to answer.") {
        init {
            initCause(cause)
        }
    }

    class AuthenticationFailed : SimpleFinException("Your Actual session is no longer valid. Sign in again to resume syncing.")
    class RedirectRefused : SimpleFinException("The server redirected the request. Enter the final server URL directly.")
    class HttpStatus(val status: Int) : SimpleFinException("The server returned HTTP $status.")
    class ResponseTooLarge : SimpleFinException("The server response was larger than this app accepts.")
    class InvalidResponse : SimpleFinException("The server returned a response this app could not read.")

    /** The server answered with Actual's `{status: "error", reason}` envelope. */
    class ServerRejected(val status: Int?, val reason: String?) :
        SimpleFinException("The server refused the request" + (reason?.let { " ($it)" } ?: "") + ".")

    /** `/simplefin/accounts` reported a provider error (`error_code`). */
    class ProviderError(val errorCode: String, val errorType: String?) :
        SimpleFinException("SimpleFIN reported an error ($errorCode).")

    /** The server answered 404, 405 or 501, or has no SimpleFIN setup token. */
    class NotConfigured : SimpleFinException("Connect SimpleFIN on your server to sync accounts.")

    class NotLinked : SimpleFinException("This account is not linked to a bank.")
    class NotSimpleFinLinked : SimpleFinException("Only SimpleFIN-linked accounts can sync here.")
    class MissingBankId : SimpleFinException("The SimpleFIN account has no bank id.")

    /** Rows the sync could not read safely; nothing was saved. */
    class UnresolvedProblems(val problems: List<BankSyncProblem>) :
        SimpleFinException("Bank sync found transactions it could not safely read. Nothing was saved.")
}

/** Whether the connected Actual server can drive SimpleFIN bank sync. */
enum class SimpleFinServerSupport {
    /** The server answered and has a SimpleFIN setup token. */
    CONFIGURED,

    /** The server answered and has no SimpleFIN setup token. */
    NOT_CONFIGURED,

    /** 404, 405 or 501: the server does not host the SimpleFIN routes. */
    UNSUPPORTED,
}

/** An account on the SimpleFIN side, as `/simplefin/accounts` returns it. */
data class SimpleFinRemoteAccount(
    val accountId: String,
    val name: String,
    /** Raw decimal balance string from the bridge, preferred over the server-computed starting balance. */
    val balance: String? = null,
    val currency: String? = null,
    val institution: String? = null,
    /** The SimpleFIN organization display name: the `banks.name` half of the `(bank_id, name)` key. */
    val orgName: String? = null,
    val orgDomain: String? = null,
    val orgId: String? = null,
)

/** Provider values kept so a per-account custom mapping can read any field name. */
sealed class RawValue {
    data class Text(val value: String) : RawValue()
    data class Integer(val value: Long) : RawValue()
    data class Number(val value: Double) : RawValue()
    data class Flag(val value: Boolean) : RawValue()
    data class Obj(val value: Map<String, RawValue>) : RawValue()
    data class Arr(val value: List<RawValue>) : RawValue()
    data object Null : RawValue()

    /** Scalar lookup for custom mapping; objects and arrays cannot be date, payee or notes sources. */
    val scalarString: String?
        get() = when (this) {
            is Text -> value
            is Integer -> value.toString()
            is Number -> if (value == Math.rint(value) && Math.abs(value) < 9.0e18) value.toLong().toString() else value.toString()
            is Flag -> if (value) "true" else "false"
            is Obj, is Arr, Null -> null
        }

    val unixSeconds: Long?
        get() = when (this) {
            is Integer -> value
            is Number -> value.toLong()
            is Text -> value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong() ?: SimpleFinDays.unixSeconds(value)
            is Flag, is Obj, is Arr, Null -> null
        }
}

/** Top-level provider fields plus `extra` flattened one level (a top-level key wins). */
class RawFields(values: Map<String, RawValue> = emptyMap()) {
    val values: Map<String, RawValue>

    init {
        val merged = LinkedHashMap(values)
        val extra = values["extra"]
        if (extra is RawValue.Obj) {
            for ((key, value) in extra.value) if (key !in merged) merged[key] = value
        }
        this.values = merged
    }

    operator fun get(key: String): RawValue? = values[key]
}

/** One normalized transaction row of an account download. */
data class SimpleFinRemoteTransaction(
    val id: String?,
    /** UNIX seconds, read as UTC by the mapping. */
    val dateUnixSeconds: Long?,
    /** Raw decimal string; converted to minor units with the budget currency's scale, never times 100. */
    val amount: String?,
    /** Explicit mismatches with the budget currency fail closed before amount conversion. */
    val currency: String?,
    val payeeName: String?,
    val notes: String?,
    /** Booked (posted) versus pending; null when the bridge did not say. */
    val booked: Boolean?,
    val accountId: String? = null,
    val rawFields: RawFields = RawFields(),
)

/** Current balance evidence: the decimal string and its currency stay together until conversion. */
data class SimpleFinBalanceAmount(val amount: String, val currency: String?)

/** Per-account download from `/simplefin/transactions`. */
data class SimpleFinAccountDownload(
    val transactions: List<SimpleFinRemoteTransaction>,
    val currentBalance: SimpleFinBalanceAmount? = null,
    /** Server-computed opening balance in minor units; only trusted for two-decimal currencies. */
    val startingBalance: Long? = null,
    val errorType: String? = null,
    val errorCode: String? = null,
) {
    val hasError: Boolean get() = errorCode != null
}

/** The whole `/simplefin/transactions` response: account-keyed downloads plus an optional whole-request failure. */
data class SimpleFinTransactionsResponse(
    val downloads: Map<String, SimpleFinAccountDownload>,
    val errorType: String? = null,
    val errorCode: String? = null,
) {
    val hasWholeRequestError: Boolean get() = errorCode != null
}

/** Calendar helpers over bridge timestamps. */
object SimpleFinDays {
    private val DAY = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")

    /** Noon UTC of a strict `YYYY-MM-DD` day, or null when it is not a real date. */
    fun unixSeconds(day: String): Long? {
        if (!DAY.matches(day)) return null
        return try {
            LocalDate.of(day.substring(0, 4).toInt(), day.substring(5, 7).toInt(), day.substring(8, 10).toInt())
                .atTime(12, 0).toEpochSecond(ZoneOffset.UTC)
        } catch (error: DateTimeException) {
            null
        }
    }

    /** UTC calendar day (`YYYYMMDD`) of UNIX seconds, as the server's `toISOString().split('T')[0]`. */
    fun dayId(unixSeconds: Long): String =
        ImportDays.dayId(Instant.ofEpochSecond(unixSeconds).atZone(ZoneOffset.UTC).toLocalDate())
}
