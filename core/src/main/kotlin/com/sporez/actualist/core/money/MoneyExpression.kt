package com.sporez.actualist.core.money

/**
 * A tiny pure evaluator for amount fields: `120+35.5`, `100 - 12.25 + 3`, `(4.99)+1`. Only `+` and `-`
 * between plain amounts, as Actualist's assignment keypad offers. Each operand goes through [Money.parse],
 * so its rules (no silent rounding, the input maximum) are unchanged; text without an operator is exactly
 * [Money.parse]. A trailing `=` is ignored.
 */
object MoneyExpression {
    /** Whether [text] is an expression of more than one operand (so a UI can show its result). */
    fun isExpression(text: String): Boolean = operands(text.trim().removeSuffix("=").trim())?.size?.let { it > 1 } == true

    /** The result in minor units, or null when [text] is not a valid amount or expression. */
    fun evaluate(text: String, decimals: Int = 2): Long? {
        val body = text.trim().removeSuffix("=").trim()
        val parts = operands(body) ?: return null
        if (parts.size == 1) return Money.parse(body, decimals)
        var total = 0L
        for ((sign, operand) in parts) {
            val value = Money.parse(operand, decimals) ?: return null
            total = try {
                Math.addExact(total, sign * value)
            } catch (error: ArithmeticException) {
                return null
            }
            if (kotlin.math.abs(total) > Money.MAXIMUM_INPUT) return null
        }
        return total
    }

    /** Splits at binary `+`/`-` (one that follows an operand); null for empty text. A leading or doubled sign stays with its operand. */
    private fun operands(text: String): List<Pair<Long, String>>? {
        if (text.isEmpty()) return null
        val result = ArrayList<Pair<Long, String>>()
        var sign = 1L
        val current = StringBuilder()
        var depth = 0
        for (char in text) {
            when {
                char == '(' -> { depth++; current.append(char) }
                char == ')' -> { depth--; current.append(char) }
                (char == '+' || char == '-') && depth == 0 && current.isNotBlank() -> {
                    result.add(sign to current.toString())
                    current.setLength(0)
                    sign = if (char == '-') -1L else 1L
                }
                else -> current.append(char)
            }
        }
        if (current.isBlank()) return null
        result.add(sign to current.toString())
        return result
    }
}
