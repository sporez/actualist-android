package com.sporez.actualist.core.transactions

import java.text.BreakIterator

/** A category name with its leading emoji split off, as Actualist's `actualistCategoryNameParts`. */
data class CategoryNameParts(val emoji: String?, val name: String)

/** The payee a feed row shows, and whether it is a placeholder (shown in italics). */
data class RowPayee(val text: String, val placeholder: Boolean)

/** How a row's cleared state is marked (Actualist's `TransactionRowSemantics.Status`). */
enum class RowStatus { UNCLEARED, CLEARED, RECONCILED }

/**
 * Pure presentation rules for feed rows, ported from Actualist's `TransactionRowSemantics`: the
 * split parent's payee, the left/over remainder, the status marker and the category emoji.
 */
object TransactionRowPresentation {
    const val SPLIT_NO_PAYEE = "Split (no payee)"
    const val NO_PAYEE = "No payee"

    fun status(row: TransactionRow): RowStatus = when {
        row.reconciled -> RowStatus.RECONCILED
        row.cleared -> RowStatus.CLEARED
        else -> RowStatus.UNCLEARED
    }

    /**
     * A split parent shows its children's most common payee (the first to reach the highest count)
     * with "(+N more)" for the others, or "Split (no payee)"; any other row shows its payee.
     */
    fun payee(row: TransactionRow): RowPayee {
        if (!row.isParent) {
            val text = row.displayPayee.trim()
            return if (text.isEmpty()) RowPayee(NO_PAYEE, true) else RowPayee(text, false)
        }
        val counts = LinkedHashMap<String, Int>()
        var best: TransactionRow? = null
        var bestCount = 0
        for (child in row.children) {
            val id = child.payeeId?.trim().orEmpty()
            if (id.isEmpty()) continue
            val next = (counts[id] ?: 0) + 1
            counts[id] = next
            if (next > bestCount) {
                bestCount = next
                best = child
            }
        }
        val name = best?.displayPayee?.trim().orEmpty()
        if (best == null || name.isEmpty()) return RowPayee(SPLIT_NO_PAYEE, true)
        val hidden = counts.size - 1
        return RowPayee(if (hidden > 0) "$name (+$hidden more)" else name, false)
    }

    /** The parent minus its children, or null when this is not a split parent or the children add up. */
    fun splitRemaining(row: TransactionRow): Long? {
        if (!row.isParent) return null
        return (row.amount - row.children.sumOf { it.amount }).takeIf { it != 0L }
    }

    /** "$5.00 left" or "$2.00 over" for a split parent whose children do not add up; null otherwise. */
    fun splitStatusText(row: TransactionRow, format: (Long) -> String): String? =
        splitRemaining(row)?.let { SplitRemainingPresentation.statusText(it, row.amount, format) }

    fun categoryNameParts(raw: String): CategoryNameParts {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return CategoryNameParts(null, trimmed)
        val breaks = BreakIterator.getCharacterInstance()
        breaks.setText(trimmed)
        val end = breaks.next()
        if (end <= 0 || end > trimmed.length) return CategoryNameParts(null, trimmed)
        val first = trimmed.substring(0, end)
        if (!isLeadingEmoji(first)) return CategoryNameParts(null, trimmed)
        val rest = trimmed.substring(end).trim()
        return CategoryNameParts(first, rest.ifEmpty { trimmed })
    }

    private fun isLeadingEmoji(cluster: String): Boolean {
        var index = 0
        while (index < cluster.length) {
            val codePoint = cluster.codePointAt(index)
            if (codePoint == 0xFE0F || codePoint >= 0x1F000 || Character.getType(codePoint) == Character.OTHER_SYMBOL.toInt()) return true
            index += Character.charCount(codePoint)
        }
        return false
    }
}
