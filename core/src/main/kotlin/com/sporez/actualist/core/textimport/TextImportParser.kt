package com.sporez.actualist.core.textimport

import com.sporez.actualist.core.money.Money
import java.time.LocalDate

enum class TextImportDirection { SPEND, INFLOW, TRANSFER }

/** What a line of text said: an amount in positive minor units and the loose words around it. */
data class TextImportParse(
    val amountMinorUnits: Long,
    val direction: TextImportDirection?,
    val payeeText: String? = null,
    val accountText: String? = null,
    val destinationAccountText: String? = null,
    val categoryText: String? = null,
    val notes: String? = null,
    val date: LocalDate? = null,
)

/**
 * Actualist's Shortcuts text importer (`ShortcutTextImportParser`): "spent $12.50 on Fresh Market
 * in Checking", "received 1,200 from Employer", "transfer $50 from Checking to Savings". An ISO
 * date (yyyy-m-d) anywhere in the text is taken as the date, where Actualist only strips it.
 * Returns null when no amount is found.
 */
object TextImportParser {
    private val whitespace = Regex("\\s+")
    private val transfer = Regex("""^transfer\s+(\$?[\d,]+(?:\.\d{1,2})?)\s+from\s+(.+?)\s+to\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val amount = Regex("""\$?-?[\d,]+(?:\.\d{1,2})?""")
    private val isoDate = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
    private val keywords = listOf("spent", "spend", "received", "inflow", "got")

    fun parse(text: String): TextImportParse? {
        val normalized = text.replace(whitespace, " ").trim()
        if (normalized.isEmpty()) return null

        parseTransfer(normalized)?.let { return it }

        val date = isoDate.find(normalized)?.let { match ->
            runCatching {
                LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
            }.getOrNull()
        }
        val withoutDates = normalized.replace(isoDate, " ").replace(whitespace, " ").trim()
        val raw = amount.find(withoutDates)?.value ?: return null
        val parsed = Money.parse(raw) ?: return null
        val minorUnits = kotlin.math.abs(parsed)

        var working = removing(raw, withoutDates)
        val direction = parseDirection(working) ?: TextImportDirection.SPEND
        for (keyword in keywords) working = removing(keyword, working)

        var payeeText: String? = null
        var accountText: String? = null
        var categoryText: String? = null

        capture(listOf("on", "at"), working)?.let { (value, rest) ->
            payeeText = value
            working = rest
        }
        capture(listOf("in"), working)?.let { (value, rest) ->
            accountText = value
            working = rest
        }
        var leftovers = working.split(" ").filter { it.isNotEmpty() }
        if (payeeText == null && leftovers.isNotEmpty()) {
            payeeText = leftovers.first()
            leftovers = leftovers.drop(1)
        }
        if (leftovers.size == 1) {
            if (accountText == null) categoryText = leftovers[0]
        } else if (leftovers.size > 1) {
            categoryText = leftovers[0]
            if (accountText == null) accountText = leftovers[1]
        }

        return TextImportParse(
            amountMinorUnits = minorUnits,
            direction = direction,
            payeeText = payeeText,
            accountText = accountText,
            categoryText = categoryText,
            date = date,
        )
    }

    private fun parseTransfer(text: String): TextImportParse? {
        val match = transfer.find(text) ?: return null
        val minorUnits = Money.parse(match.groupValues[1]) ?: return null
        return TextImportParse(
            amountMinorUnits = kotlin.math.abs(minorUnits),
            direction = TextImportDirection.TRANSFER,
            accountText = match.groupValues[2].trim(),
            destinationAccountText = match.groupValues[3].trim(),
        )
    }

    private fun parseDirection(text: String): TextImportDirection? {
        val lower = text.lowercase()
        if (lower.contains("received") || lower.contains("inflow") || lower.contains("got")) return TextImportDirection.INFLOW
        if (lower.contains("spent") || lower.contains("spend")) return TextImportDirection.SPEND
        return null
    }

    private fun removing(snippet: String, text: String): String =
        Regex(Regex.escape(snippet), RegexOption.IGNORE_CASE).replace(text, " ").replace(whitespace, " ").trim()

    /** The words after the first of [prefixes] up to the next in/on/at, and the text without that phrase. */
    private fun capture(prefixes: List<String>, text: String): Pair<String, String>? {
        for (prefix in prefixes) {
            val pattern = Regex("""\b""" + Regex.escape(prefix) + """\s+(.+?)(?=\s+(?:in|on|at)\b|$)""", RegexOption.IGNORE_CASE)
            val match = pattern.find(text) ?: continue
            return match.groupValues[1].trim() to removing(match.groupValues[0], text)
        }
        return null
    }
}
