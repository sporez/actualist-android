package com.sporez.actualist.core.banksync.bridge

import com.sporez.actualist.core.banksync.RawFields
import com.sporez.actualist.core.banksync.RawValue
import com.sporez.actualist.core.banksync.SimpleFinAccountDownload
import com.sporez.actualist.core.banksync.SimpleFinBalanceAmount
import com.sporez.actualist.core.banksync.SimpleFinDays
import com.sporez.actualist.core.banksync.SimpleFinException
import com.sporez.actualist.core.banksync.SimpleFinRemoteAccount
import com.sporez.actualist.core.banksync.SimpleFinRemoteTransaction
import com.sporez.actualist.core.banksync.SimpleFinServerSupport
import com.sporez.actualist.core.banksync.SimpleFinTransactionsResponse
import com.sporez.actualist.core.banksync.SimpleFinTransport
import com.sporez.actualist.core.banksync.SimpleFinWire
import com.sporez.actualist.core.server.ActualServerClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Direct SimpleFIN bridge transport, the fallback Actualist uses when the Actual server does not host the
 * `simplefin` routes and the user claimed a setup token on this device. Port of Actualist's `SimpleFINBridgeClient`.
 *
 * The bridge API is `GET {access base}/accounts` with HTTP Basic auth: `?balances-only=1` for the account list, and
 * `?pending=1&start-date=<unix>&end-date=<unix>` for transactions. Redirects are refused and the reply is capped
 * (16 MiB). The `token` argument of every method is ignored (there is no Actual session involved), and [status]
 * always reports CONFIGURED: holding a claimed key is the configuration.
 *
 * [now] returns UNIX seconds and supplies the request's `end-date`.
 */
class SimpleFinBridgeTransport(
    private val credentials: SimpleFinBridgeCredentials,
    private val http: OkHttpClient = ActualServerClient.defaultHttpClient(),
    private val maximumResponseBytes: Long = 16L * 1024 * 1024,
    private val allowLoopbackHttp: Boolean = false,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : SimpleFinTransport {
    private val client: OkHttpClient = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    init {
        if (!credentials.baseUrl.isHttps && !(allowLoopbackHttp && credentials.baseUrl.host in LOOPBACK)) {
            throw SimpleFinException.InsecureUrl()
        }
    }

    override fun status(token: String): SimpleFinServerSupport = SimpleFinServerSupport.CONFIGURED

    override fun accounts(token: String): List<SimpleFinRemoteAccount>? {
        val set = run(accountsUrl(null, null))
        return set.accounts.map { it.remoteAccount() }
    }

    override fun transactions(token: String, accountIds: List<String>, startDates: List<String>): SimpleFinTransactionsResponse? {
        val startsByAccount = LinkedHashMap<String, Long>()
        accountIds.forEachIndexed { index, accountId ->
            if (accountId !in startsByAccount) {
                startsByAccount[accountId] = if (index in startDates.indices) unixSeconds(startDates[index]) else 0L
            }
        }
        val requestStart = startsByAccount.values.minOrNull() ?: 0L
        val set = run(accountsUrl(requestStart, now()))
        val downloads = LinkedHashMap<String, SimpleFinAccountDownload>()
        for (accountId in accountIds) {
            val account = set.accounts.firstOrNull { it.id == accountId }
            if (account == null) {
                downloads[accountId] = SimpleFinAccountDownload(emptyList(), null, null, null, "ACCOUNT_MISSING")
                continue
            }
            val accountError = set.errlist.firstOrNull { it.accountId == accountId && it.code != null }
            val orgName = account.orgName
            val needsAttention = orgName != null && set.errors.any { it.startsWith("Connection to $orgName may need attention") }
            val errorCode = accountError?.code
                ?: (if (needsAttention) "ACCOUNT_NEEDS_ATTENTION" else null)
                ?: (if (account.transactions == null) "ACCOUNT_MISSING" else null)
            val seen = HashSet<String>()
            val accountStart = startsByAccount[accountId] ?: requestStart
            val rows = ArrayList<SimpleFinRemoteTransaction>()
            for (transaction in (account.transactions.orEmpty() + account.pending.orEmpty())) {
                val id = transaction.id
                if (id != null && !seen.add(id)) continue
                val row = transaction.remoteTransaction(account.currency)
                val seconds = row.dateUnixSeconds
                if (seconds == null || seconds >= accountStart) rows.add(row)
            }
            downloads[accountId] = SimpleFinAccountDownload(
                transactions = rows,
                currentBalance = account.balance?.let { SimpleFinBalanceAmount(it, account.currency) },
                startingBalance = null,
                errorType = errorCode,
                errorCode = errorCode,
            )
        }
        return SimpleFinTransactionsResponse(downloads)
    }

    // region Requests

    private fun accountsUrl(startDate: Long?, endDate: Long?): HttpUrl {
        val builder = credentials.baseUrl.newBuilder().addPathSegment("accounts")
        if (startDate != null) {
            builder.addQueryParameter("pending", "1")
            builder.addQueryParameter("start-date", startDate.toString())
            builder.addQueryParameter("end-date", maxOf(endDate ?: 0L, startDate).toString())
        } else {
            builder.addQueryParameter("balances-only", "1")
        }
        return builder.build()
    }

    private fun run(url: HttpUrl): BridgeAccountSet {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", credentials.basicAuthorization())
            .get()
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (error: SocketTimeoutException) {
            throw SimpleFinException.TimedOut(error)
        } catch (error: IOException) {
            throw SimpleFinException.Unreachable(error)
        }
        response.use {
            if (it.isRedirect) throw SimpleFinException.RedirectRefused()
            val code = it.code
            if (it.body.contentLength() > maximumResponseBytes) throw SimpleFinException.ResponseTooLarge()
            val bytes = try {
                val source = it.body.source()
                if (source.request(maximumResponseBytes + 1)) throw SimpleFinException.ResponseTooLarge()
                source.readByteArray()
            } catch (error: SocketTimeoutException) {
                throw SimpleFinException.TimedOut(error)
            } catch (error: IOException) {
                throw SimpleFinException.Unreachable(error)
            }
            when (code) {
                200 -> return parse(String(bytes, Charsets.UTF_8))
                // 402 payment required and any other status surface as the HTTP status; 403 means the key was revoked.
                403 -> throw SimpleFinException.AuthenticationFailed()
                else -> throw SimpleFinException.HttpStatus(code)
            }
        }
    }

    // endregion

    // region Payload

    private class BridgeError(val code: String?, val accountId: String?)

    private class BridgeAccount(
        val id: String,
        val name: String?,
        val balance: String?,
        val currency: String?,
        val institution: String?,
        val orgName: String?,
        val orgDomain: String?,
        val orgId: String?,
        val transactions: List<BridgeTransaction>?,
        val pending: List<BridgeTransaction>?,
    ) {
        fun remoteAccount() = SimpleFinRemoteAccount(
            accountId = id,
            name = name ?: "",
            balance = balance,
            currency = currency,
            institution = institution,
            orgName = orgName,
            orgDomain = orgDomain ?: orgId,
            orgId = orgId,
        )
    }

    private class BridgeAccountSet(val errors: List<String>, val errlist: List<BridgeError>, val accounts: List<BridgeAccount>)

    private class BridgeTransaction(val obj: JsonObject) {
        val id: String? = text(obj["id"])

        fun remoteTransaction(currency: String?): SimpleFinRemoteTransaction {
            val posted = SimpleFinWire.flexSeconds(obj["posted"])
            val transactedAt = SimpleFinWire.flexSeconds(obj["transacted_at"])
            val amount = SimpleFinWire.flexString(obj["amount"])
            val description = text(obj["description"])
            val payee = text(obj["payee"])
            val pending = SimpleFinWire.flexBool(obj["pending"])
            val extra = obj["extra"] as? JsonObject
            val extraPayee = scalarText(extra?.get("payee"))
            val extraNotes = scalarText(extra?.get("notes"))
            val payeeName = (extraPayee ?: payee ?: description)?.trim()
            val date: Long? = if (pending == true || posted == 0L) transactedAt ?: posted else posted ?: transactedAt

            val raw = LinkedHashMap<String, RawValue>()
            if (extra != null) for ((key, value) in extra) raw[key] = SimpleFinWire.rawValue(value)
            fun fill(key: String, value: RawValue?) {
                if (value != null && key !in raw) raw[key] = value
            }
            fill("id", id?.let { RawValue.Text(it) })
            fill("posted", posted?.let { RawValue.Integer(it) })
            fill("transacted_at", transactedAt?.let { RawValue.Integer(it) })
            fill("amount", amount?.let { RawValue.Text(it) })
            fill("description", description?.let { RawValue.Text(it) })
            fill("payee", payee?.let { RawValue.Text(it) })
            fill("pending", pending?.let { RawValue.Flag(it) })
            return SimpleFinRemoteTransaction(
                id = id,
                dateUnixSeconds = date,
                amount = amount,
                currency = currency,
                payeeName = if (payeeName.isNullOrEmpty()) null else payeeName,
                notes = extraNotes,
                booked = pending?.let { !it },
                accountId = null,
                rawFields = RawFields(raw),
            )
        }
    }

    private fun parse(textBody: String): BridgeAccountSet {
        val parsed: JsonObject? = try {
            Json.parseToJsonElement(textBody) as? JsonObject
        } catch (error: Exception) {
            null
        }
        val root = parsed ?: throw SimpleFinException.InvalidResponse()
        val errors = (root["errors"] as? JsonArray).orEmpty().mapNotNull { text(it) }
        val errlist = (root["errlist"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            BridgeError(text(obj["code"]), text(obj["account_id"]))
        }
        val accounts = (root["accounts"] as? JsonArray).orEmpty().map { element ->
            val obj = element as? JsonObject ?: throw SimpleFinException.InvalidResponse()
            val id = text(obj["id"]) ?: throw SimpleFinException.InvalidResponse()
            val org = obj["org"] as? JsonObject
            BridgeAccount(
                id = id,
                name = text(obj["name"]),
                balance = SimpleFinWire.flexString(obj["balance"]),
                currency = text(obj["currency"]),
                institution = text(obj["institution"]),
                orgName = text(org?.get("name")),
                orgDomain = text(org?.get("domain")),
                orgId = text(org?.get("id")),
                transactions = transactionList(obj["transactions"]),
                pending = transactionList(obj["pending"]),
            )
        }
        return BridgeAccountSet(errors, errlist, accounts)
    }

    private fun transactionList(element: JsonElement?): List<BridgeTransaction>? {
        val array = element as? JsonArray ?: return null
        return array.map { BridgeTransaction(it as? JsonObject ?: throw SimpleFinException.InvalidResponse()) }
    }

    // endregion

    private companion object {
        val LOOPBACK = setOf("localhost", "127.0.0.1", "::1", "[::1]")

        /** `YYYY-MM-DD` (the lookback start the shared pipeline builds) to noon-UTC UNIX seconds; 0 when unreadable. */
        fun unixSeconds(day: String): Long = SimpleFinDays.unixSeconds(day) ?: 0L

        /** A JSON string; anything else is null. */
        fun text(element: JsonElement?): String? {
            val primitive = element as? JsonPrimitive ?: return null
            return if (element !is JsonNull && primitive.isString) primitive.content else null
        }

        /** A scalar rendered as text (strings, numbers, booleans); objects, arrays and null are null. */
        fun scalarText(element: JsonElement?): String? {
            if (element == null) return null
            return SimpleFinWire.rawValue(element).scalarString
        }
    }
}
