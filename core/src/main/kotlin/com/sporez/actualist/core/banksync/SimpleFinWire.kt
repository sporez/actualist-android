package com.sporez.actualist.core.banksync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Defensive decoding of the Actual server's the `simplefin` routes payloads, ported from Actualist's
 * `ActualServerSimpleFINClient` and `SimpleFINFlexibleValues`. Bridge and server payloads ship numbers as
 * strings, numbers or nulls depending on version; nothing here guesses into data.
 */
internal object SimpleFinWire {
    private val json = Json

    fun parseRoot(text: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject ?: throw SimpleFinException.InvalidResponse()
    } catch (error: SimpleFinException) {
        throw error
    } catch (error: Exception) {
        throw SimpleFinException.InvalidResponse()
    }

    // region Flexible scalars

    private fun scalar(element: JsonElement?): JsonPrimitive? = (element as? JsonPrimitive)?.takeIf { it !is JsonNull }

    /** A string, or a number surfaced as its literal text. Null for anything else. */
    fun flexString(element: JsonElement?): String? {
        val primitive = scalar(element) ?: return null
        if (primitive.isString) return primitive.content
        if (primitive.booleanOrNull != null) return null
        return primitive.content.takeIf { it.toDoubleOrNull() != null }
    }

    /** `true`, `1` or `"true"`; null when absent or unreadable. */
    fun flexBool(element: JsonElement?): Boolean? {
        val primitive = scalar(element) ?: return null
        primitive.booleanOrNull?.let { return it }
        if (primitive.isString) return primitive.content.lowercase() in setOf("1", "true", "yes")
        val number = primitive.content.toLongOrNull() ?: return null
        return number != 0L
    }

    /** UNIX seconds from a number, a numeric string or a `YYYY-MM-DD` day. */
    fun flexSeconds(element: JsonElement?): Long? {
        val primitive = scalar(element) ?: return null
        if (!primitive.isString && primitive.booleanOrNull != null) return null
        val text = primitive.content
        text.toLongOrNull()?.let { return it }
        text.toDoubleOrNull()?.let { return if (it.isFinite()) it.toLong() else null }
        return if (primitive.isString) SimpleFinDays.unixSeconds(text) else null
    }

    private fun flexLong(element: JsonElement?): Long? {
        val primitive = scalar(element) ?: return null
        if (!primitive.isString && primitive.booleanOrNull != null) return null
        val text = primitive.content
        text.toLongOrNull()?.let { return it }
        return text.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
    }

    private fun string(element: JsonElement?): String? {
        val primitive = scalar(element) ?: return null
        return if (primitive.isString) primitive.content else null
    }

    fun rawValue(element: JsonElement): RawValue = when (element) {
        is JsonNull -> RawValue.Null
        is JsonObject -> RawValue.Obj(element.entries.associate { it.key to rawValue(it.value) })
        is JsonArray -> RawValue.Arr(element.map(::rawValue))
        is JsonPrimitive -> when {
            element.isString -> RawValue.Text(element.content)
            element.booleanOrNull != null -> RawValue.Flag(element.booleanOrNull == true)
            element.content.toLongOrNull() != null -> RawValue.Integer(element.content.toLong())
            element.content.toDoubleOrNull() != null -> RawValue.Number(element.content.toDouble())
            else -> RawValue.Null
        }
    }

    // endregion

    // region Status and accounts

    /** `{configured}` bare, or inside Actual's `{status, data}` envelope. */
    fun configured(root: JsonObject): Boolean {
        (root["configured"] as? JsonPrimitive)?.booleanOrNull?.let { return it }
        val data = root["data"] as? JsonObject ?: throw SimpleFinException.InvalidResponse()
        return (data["configured"] as? JsonPrimitive)?.booleanOrNull ?: throw SimpleFinException.InvalidResponse()
    }

    /** Accepts both the raw SimpleFIN account (`id`, `org`) and the normalized link DTO (`account_id`). */
    fun accounts(root: JsonObject): List<SimpleFinRemoteAccount> {
        val data = root["data"] as? JsonObject
        val errorCode = string(data?.get("error_code"))
        if (errorCode != null) throw SimpleFinException.ProviderError(errorCode, string(data?.get("error_type")))
        val list = data?.get("accounts") as? JsonArray ?: return emptyList()
        return list.map { element ->
            val account = element as? JsonObject ?: throw SimpleFinException.InvalidResponse()
            val id = string(account["account_id"]) ?: string(account["id"]) ?: throw SimpleFinException.InvalidResponse()
            val name = string(account["name"]) ?: throw SimpleFinException.InvalidResponse()
            val org = account["org"] as? JsonObject
            SimpleFinRemoteAccount(
                accountId = id,
                name = name,
                balance = flexString(account["balance"]),
                currency = string(account["currency"]),
                institution = string(account["institution"]),
                orgName = string(org?.get("name")),
                orgDomain = string(org?.get("domain")) ?: string(account["orgDomain"]),
                orgId = string(org?.get("id")) ?: string(account["orgId"]),
            )
        }
    }

    // endregion

    // region Transactions

    /**
     * Transaction responses are account-keyed maps: every key except `errors`, `error_type` and `error_code`
     * is a remote account id, decoded from `data` when present and from the top level otherwise. A null or
     * unreadable account entry is skipped; the caller maps its absence to `ACCOUNT_MISSING`.
     */
    fun transactions(root: JsonObject): SimpleFinTransactionsResponse {
        val payload = if ("data" in root) (root["data"] as? JsonObject ?: throw SimpleFinException.InvalidResponse()) else root
        val batchErrors = LinkedHashMap<String, List<Pair<String?, String?>>>()
        (payload["errors"] as? JsonObject)?.let { errors ->
            for ((accountId, list) in errors) {
                val entries = list as? JsonArray ?: continue
                batchErrors[accountId] = entries.mapNotNull { entry ->
                    val obj = entry as? JsonObject ?: return@mapNotNull null
                    string(obj["error_type"]) to string(obj["error_code"])
                }
            }
        }
        val downloads = LinkedHashMap<String, SimpleFinAccountDownload>()
        for ((key, value) in payload) {
            if (key == "errors" || key == "error_type" || key == "error_code") continue
            val entry = value as? JsonObject ?: continue
            val parsed = accountEntry(entry) ?: continue
            var errorType = parsed.errorType
            var errorCode = parsed.errorCode
            if (errorCode == null) {
                val first = batchErrors[key]?.firstOrNull()
                if (first != null) {
                    errorType = errorType ?: first.first
                    errorCode = first.second
                }
            }
            // Actual rejects a missing collection; an empty list is a successful empty download.
            if (errorCode == null && !parsed.hasTransactionCollection) {
                errorType = "ACCOUNT_MISSING"
                errorCode = "ACCOUNT_MISSING"
            }
            downloads[key] = SimpleFinAccountDownload(
                transactions = parsed.transactions,
                currentBalance = parsed.currentBalance,
                startingBalance = parsed.startingBalance,
                errorType = errorType,
                errorCode = errorCode,
            )
        }
        // Accounts that only appear in the batch error map still get an empty download so callers see the failure.
        for ((accountId, list) in batchErrors) {
            val first = list.firstOrNull() ?: continue
            if (accountId in downloads) continue
            downloads[accountId] = SimpleFinAccountDownload(emptyList(), errorType = first.first, errorCode = first.second)
        }
        return SimpleFinTransactionsResponse(downloads, string(payload["error_type"]), string(payload["error_code"]))
    }

    private class ParsedEntry(
        val transactions: List<SimpleFinRemoteTransaction>,
        val hasTransactionCollection: Boolean,
        val currentBalance: SimpleFinBalanceAmount?,
        val startingBalance: Long?,
        val errorType: String?,
        val errorCode: String?,
    )

    private fun accountEntry(entry: JsonObject): ParsedEntry? {
        val transactionsElement = entry["transactions"]
        var all: JsonArray? = null
        if (transactionsElement != null && transactionsElement !is JsonNull) {
            val envelope = transactionsElement as? JsonObject ?: return null
            val allElement = envelope["all"]
            if (allElement != null && allElement !is JsonNull) all = allElement as? JsonArray ?: return null
        }
        val transactions = ArrayList<SimpleFinRemoteTransaction>()
        if (all != null) {
            for (element in all) transactions.add(transaction(element as? JsonObject ?: return null))
        }
        val balancesElement = entry["balances"]
        var balance: SimpleFinBalanceAmount? = null
        if (balancesElement != null && balancesElement !is JsonNull) {
            val balances = balancesElement as? JsonArray ?: return null
            for (element in balances) {
                val amountObject = (element as? JsonObject)?.get("balanceAmount") as? JsonObject ?: continue
                val amount = flexString(amountObject["amount"]) ?: continue
                balance = SimpleFinBalanceAmount(amount, string(amountObject["currency"]))
                break
            }
        }
        return ParsedEntry(
            transactions = transactions,
            hasTransactionCollection = all != null,
            currentBalance = balance,
            startingBalance = flexLong(entry["startingBalance"]),
            errorType = string(entry["error_type"]),
            errorCode = string(entry["error_code"]),
        )
    }

    /**
     * The current sync-server normalizes bridge rows to `transactionId`, nested `transactionAmount.amount`
     * and a `YYYY-MM-DD` `date`; raw bridge aliases (`id`, `amount`, `posted`, `transacted_at`) stay readable.
     */
    private fun transaction(obj: JsonObject): SimpleFinRemoteTransaction {
        val amountObject = obj["transactionAmount"] as? JsonObject
        return SimpleFinRemoteTransaction(
            id = string(obj["id"]) ?: string(obj["transactionId"]),
            dateUnixSeconds = flexSeconds(obj["date"]) ?: flexSeconds(obj["posted"]) ?: flexSeconds(obj["transacted_at"]),
            amount = flexString(obj["amount"]) ?: flexString(amountObject?.get("amount")),
            currency = string(amountObject?.get("currency")) ?: string(obj["currency"]),
            payeeName = string(obj["payeeName"]) ?: string(obj["payee_name"]),
            notes = string(obj["notes"]) ?: string(obj["memo"]),
            booked = flexBool(obj["booked"]) ?: flexBool(obj["cleared"]),
            accountId = string(obj["account"]),
            rawFields = RawFields(obj.entries.associate { it.key to rawValue(it.value) }),
        )
    }

    // endregion
}
