package com.sporez.actualist.core.money

import java.math.BigDecimal
import java.math.RoundingMode

/** Actual's `numberFormat` preference: the grouping and decimal separators of an amount. */
enum class NumberStyle(val preference: String, val grouping: Char, val decimal: Char) {
    COMMA_DOT("comma-dot", ',', '.'),
    DOT_COMMA("dot-comma", '.', ','),
    SPACE_COMMA("space-comma", '\u202F', ','),
    APOSTROPHE_DOT("apostrophe-dot", '\u2019', '.'),
    COMMA_DOT_IN("comma-dot-in", ',', '.');

    companion object {
        fun of(preference: String?): NumberStyle? = entries.firstOrNull { it.preference == preference }
    }
}

/** One entry of Actual's bundled currency catalog (`loot-core/src/shared/currencies.ts`). */
data class MoneyCurrency(
    val code: String,
    val name: String,
    val symbol: String,
    val decimalPlaces: Int,
    val numberStyle: NumberStyle,
    val symbolFirst: Boolean,
) {
    companion object {
        /** Actual's `getCurrency`: an unknown code is the neutral "None" entry. */
        fun of(code: String?): MoneyCurrency {
            val resolved = code?.trim()?.uppercase().orEmpty()
            return all.firstOrNull { it.code == resolved } ?: all.first()
        }

        val all: List<MoneyCurrency> = listOf(
        MoneyCurrency("", "None", "", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("AED", "UAE Dirham", "د.إ", 2, NumberStyle.COMMA_DOT, false),
        MoneyCurrency("ARS", "Argentinian Peso", "Arg$", 2, NumberStyle.DOT_COMMA, true),
        MoneyCurrency("AUD", "Australian Dollar", "A$", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("BRL", "Brazilian Real", "R$", 2, NumberStyle.DOT_COMMA, true),
        MoneyCurrency("BYN", "Belarusian Ruble", "Br", 2, NumberStyle.SPACE_COMMA, false),
        MoneyCurrency("CAD", "Canadian Dollar", "CA$", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("CHF", "Swiss Franc", "Fr.", 2, NumberStyle.APOSTROPHE_DOT, true),
        MoneyCurrency("CLP", "Chilean Peso", "CLP$", 2, NumberStyle.DOT_COMMA, true),
        MoneyCurrency("CNY", "Yuan Renminbi", "¥", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("COP", "Colombian Peso", "Col$", 2, NumberStyle.DOT_COMMA, true),
        MoneyCurrency("CRC", "Costa Rican Colón", "₡", 2, NumberStyle.SPACE_COMMA, true),
        MoneyCurrency("CZK", "Czech Koruna", "Kč", 2, NumberStyle.SPACE_COMMA, false),
        MoneyCurrency("DKK", "Danish Krone", "kr", 2, NumberStyle.DOT_COMMA, false),
        MoneyCurrency("DOP", "Dominican Peso", "RD$", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("EGP", "Egyptian Pound", "ج.م", 2, NumberStyle.COMMA_DOT, false),
        MoneyCurrency("EUR", "Euro", "€", 2, NumberStyle.DOT_COMMA, false),
        MoneyCurrency("GBP", "Pound Sterling", "£", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("GTQ", "Guatemalan Quetzal", "Q", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("HKD", "Hong Kong Dollar", "HK$", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("HUF", "Hungarian Forint", "Ft", 2, NumberStyle.SPACE_COMMA, false),
        MoneyCurrency("IDR", "Indonesian Rupiah", "Rp", 2, NumberStyle.DOT_COMMA, true),
        MoneyCurrency("ILS", "Israeli New Shekel", "₪", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("INR", "Indian Rupee", "₹", 2, NumberStyle.COMMA_DOT_IN, true),
        MoneyCurrency("IRR", "Iranian Rial", "﷼", 0, NumberStyle.COMMA_DOT, false),
        MoneyCurrency("JMD", "Jamaican Dollar", "J$", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("JPY", "Japanese Yen", "¥", 0, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("KRW", "South Korean Won", "₩", 0, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("LKR", "Sri Lankan Rupee", "Rs.", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("MDL", "Moldovan Leu", "L", 2, NumberStyle.DOT_COMMA, false),
        MoneyCurrency("MKD", "Macedonian Denar", "ден", 2, NumberStyle.DOT_COMMA, false),
        MoneyCurrency("MXN", "Mexican Peso", "$", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("MYR", "Malaysian Ringgit", "RM", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("PEN", "Peruvian Sol", "S/", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("PHP", "Philippine Peso", "₱", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("PKR", "Pakistani Rupee", "Rs.", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("PLN", "Polish Złoty", "zł", 2, NumberStyle.SPACE_COMMA, false),
        MoneyCurrency("QAR", "Qatari Riyal", "ر.ق", 2, NumberStyle.COMMA_DOT, false),
        MoneyCurrency("RON", "Romanian Leu", "lei", 2, NumberStyle.DOT_COMMA, false),
        MoneyCurrency("RSD", "Serbian Dinar", "дин", 2, NumberStyle.DOT_COMMA, false),
        MoneyCurrency("RUB", "Russian Ruble", "₽", 2, NumberStyle.SPACE_COMMA, false),
        MoneyCurrency("SAR", "Saudi Riyal", "ر.س", 2, NumberStyle.COMMA_DOT, false),
        MoneyCurrency("SEK", "Swedish Krona", "kr", 2, NumberStyle.SPACE_COMMA, false),
        MoneyCurrency("SGD", "Singapore Dollar", "S$", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("THB", "Thai Baht", "฿", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("TRY", "Turkish Lira", "₺", 2, NumberStyle.DOT_COMMA, true),
        MoneyCurrency("TWD", "New Taiwan Dollar", "NT$", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("UAH", "Ukrainian Hryvnia", "₴", 2, NumberStyle.SPACE_COMMA, false),
        MoneyCurrency("USD", "US Dollar", "$", 2, NumberStyle.COMMA_DOT, true),
        MoneyCurrency("UYU", "Uruguayan Peso", "\$U", 2, NumberStyle.DOT_COMMA, true),
        MoneyCurrency("UZS", "Uzbek Soum", "UZS", 2, NumberStyle.SPACE_COMMA, false),
        )
    }
}

/**
 * How the open budget shows and reads amounts: Actual's synced `defaultCurrencyCode`, `numberFormat`,
 * `hideFraction`, `currencySymbolPosition` and `currencySpaceBetweenAmountAndSymbol` preferences.
 * Amounts are always integer minor units scaled by [decimalPlaces]; [hideFraction] only changes display
 * (rounded half away from zero, as Intl does) and what editing text shows.
 *
 * [current] is the app-wide active format that [Money.format] and [Money.parse] default to; the UI sets
 * it when a budget opens and whenever its preferences change.
 */
data class MoneyFormat(
    val code: String = "USD",
    val symbol: String = "$",
    val decimalPlaces: Int = 2,
    val numberStyle: NumberStyle = NumberStyle.COMMA_DOT,
    val symbolFirst: Boolean = true,
    val spaceBetween: Boolean = false,
    val hideFraction: Boolean = false,
) {
    /** Fraction digits shown. */
    val displayDecimals: Int get() = if (hideFraction) 0 else decimalPlaces

    /**
     * Formats [minorUnits]. [symbolOverride] replaces the symbol (empty means none, for editing text);
     * [decimalsOverride] replaces both the scale and the shown digits (legacy callers).
     */
    fun format(minorUnits: Long, symbolOverride: String? = null, decimalsOverride: Int? = null, grouping: Boolean = true): String {
        val scale = decimalsOverride ?: decimalPlaces
        val shown = decimalsOverride ?: displayDecimals
        val negative = minorUnits < 0
        val magnitude = BigDecimal(minorUnits).abs().movePointLeft(scale).setScale(shown, RoundingMode.HALF_UP)
        val plain = magnitude.toPlainString()
        val whole = plain.substringBefore('.')
        val fraction = if (shown > 0) numberStyle.decimal.toString() + plain.substringAfter('.') else ""
        val number = (if (grouping) group(whole) else whole) + fraction
        val sign = if (negative && magnitude.signum() != 0) "-" else ""
        val mark = symbolOverride ?: symbol
        if (mark.isEmpty()) return sign + number
        val gap = if (spaceBetween) "\u202F" else ""
        return if (symbolFirst) sign + mark + gap + number else sign + number + gap + mark
    }

    private fun group(whole: String): String {
        if (whole.length <= 3) return whole
        val separator = numberStyle.grouping
        if (numberStyle != NumberStyle.COMMA_DOT_IN) return whole.reversed().chunked(3).joinToString(separator.toString()).reversed()
        val head = whole.substring(0, whole.length - 3)
        val tail = whole.substring(whole.length - 3)
        return head.reversed().chunked(2).joinToString(separator.toString()).reversed() + separator + tail
    }

    /** Fraction digits as typed in an edit field: no symbol, no grouping, this format's decimal separator. */
    fun editText(minorUnits: Long): String = format(minorUnits, symbolOverride = "", grouping = false)

    /**
     * Parses typed text (`12`, `12,5`, `1.234,56 €`, `-4.99`, `(4.99)`) into minor units. This format's
     * separators are honoured; a lone `.` or `,` that cannot be grouping (not followed by exactly three
     * digits) is also taken as the decimal mark, so pasted `12.50` still works in a comma-decimal budget.
     * More digits than the scale is an error, never a rounding.
     */
    fun parse(text: String, scaleOverride: Int? = null): Long? {
        val scale = scaleOverride ?: decimalPlaces
        var cleaned = text.trim()
        for (mark in listOf(symbol, code, "$")) if (mark.isNotEmpty()) cleaned = cleaned.replace(mark, "")
        cleaned = cleaned.filterNot { it.isWhitespace() || it == '\u202F' || it == '\u00A0' || it == '\u202A' || it == '\u202C' }
        var negative = false
        if (cleaned.startsWith("(") && cleaned.endsWith(")")) {
            negative = true
            cleaned = cleaned.substring(1, cleaned.length - 1)
        }
        if (cleaned.startsWith("-")) {
            negative = !negative
            cleaned = cleaned.substring(1)
        } else if (cleaned.startsWith("+")) {
            cleaned = cleaned.substring(1)
        }
        if (cleaned.isEmpty()) return null
        val decimalChar = numberStyle.decimal
        // Any apostrophe form is the Swiss grouping mark.
        cleaned = cleaned.replace('\'', '\u2019')
        val groupingSymbol = numberStyle.grouping.toString()
        val other = if (decimalChar == '.') ',' else '.'
        val decimalIndex = cleaned.lastIndexOf(decimalChar)
        val digits: String
        val fractionDigits: String
        if (decimalIndex >= 0) {
            digits = cleaned.substring(0, decimalIndex).replace(groupingSymbol, "")
            fractionDigits = cleaned.substring(decimalIndex + 1)
        } else {
            // No decimal mark of this format: a lone other mark not followed by exactly three digits is one.
            val otherIndex = cleaned.indexOf(other)
            val lone = otherIndex >= 0 && cleaned.indexOf(other, otherIndex + 1) < 0
            if (lone && cleaned.length - otherIndex - 1 != 3) {
                digits = cleaned.substring(0, otherIndex).replace(groupingSymbol, "")
                fractionDigits = cleaned.substring(otherIndex + 1)
            } else {
                digits = cleaned.replace(groupingSymbol, "").replace(other.toString(), "")
                fractionDigits = ""
            }
        }
        if (!digits.all { it in '0'..'9' } || !fractionDigits.all { it in '0'..'9' }) return null
        if (digits.isEmpty() && fractionDigits.isEmpty()) return null
        if (fractionDigits.length > scale) return null
        val value = try {
            BigDecimal((digits.ifEmpty { "0" }) + if (fractionDigits.isEmpty()) "" else ".$fractionDigits")
        } catch (error: NumberFormatException) {
            return null
        }
        return try {
            val minor = value.movePointRight(scale).longValueExact()
            if (minor > Money.MAXIMUM_INPUT) null else if (negative) -minor else minor
        } catch (error: ArithmeticException) {
            null
        }
    }

    companion object {
        /** The default look when a budget names no currency: dollars, as the app always showed. */
        val DEFAULT = MoneyFormat()

        /** The active format; [Money] defaults to it. */
        @Volatile
        var current: MoneyFormat = DEFAULT

        /**
         * Builds the format from budget preference values ([read] returns a synced preference by id, or null
         * when absent). An absent `defaultCurrencyCode` keeps dollars; an empty one is Actual's "None"
         * (plain numbers). When `numberFormat` or `currencySymbolPosition` are absent, the chosen
         * currency's own conventions apply.
         */
        fun fromPreferences(read: (String) -> String?): MoneyFormat {
            val codePreference = read("defaultCurrencyCode")
            val hide = read("hideFraction") == "true"
            if (codePreference == null) {
                val style = NumberStyle.of(read("numberFormat")) ?: NumberStyle.COMMA_DOT
                return DEFAULT.copy(numberStyle = style, hideFraction = hide)
            }
            val currency = MoneyCurrency.of(codePreference)
            val style = NumberStyle.of(read("numberFormat")) ?: currency.numberStyle
            val position = read("currencySymbolPosition")
            val symbolFirst = when (position) {
                "after" -> false
                "before" -> true
                else -> currency.symbolFirst
            }
            val space = when (read("currencySpaceBetweenAmountAndSymbol")) {
                "true" -> true
                "false" -> false
                else -> !symbolFirst
            }
            return MoneyFormat(currency.code, currency.symbol, currency.decimalPlaces, style, symbolFirst, space, hide)
        }
    }
}
