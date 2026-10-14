package com.sporez.actualist.core.rules

import java.util.regex.Pattern

sealed class RuleFormulaValue {
    data class Num(val value: Double) : RuleFormulaValue()
    data class Str(val value: String) : RuleFormulaValue()
    data class Flag(val value: Boolean) : RuleFormulaValue()

    val number: Double? get() = (this as? Num)?.value
}

class RuleFormulaContext(
    val fields: Map<String, RuleFormulaValue>,
    val balanceOfPrefetch: Map<String, Long>,
    val today: String,
) {
    companion object {
        val EMPTY = RuleFormulaContext(emptyMap(), emptyMap(), "")
    }
}

class RuleFormulaException(message: String) : Exception(message)

/**
 * Focused Actual 26.x split-amount formula evaluator: arithmetic, named transaction fields,
 * `INTEGER_TO_AMOUNT` and `BALANCE_OF`. Anything else fails closed by throwing.
 */
object RuleFormulaEvaluator {
    fun evaluate(formula: String, context: RuleFormulaContext): RuleFormulaValue {
        val parser = FormulaParser(formula, context)
        val value = parser.parseFormula()
        parser.expectEnd()
        if (value is RuleFormulaValue.Num) return RuleFormulaValue.Num(amountToInteger(value.value).toDouble())
        return value
    }

    /** Actual `amountToInteger(Math.round(value * 100) / 100)` at 2 decimals. */
    fun amountToInteger(value: Double): Long =
        javascriptRound(javascriptRound(value * 100).toDouble() / 100 * 100)

    /** JavaScript `Math.round`: ties toward positive infinity. */
    fun javascriptRound(value: Double): Long {
        if (!value.isFinite()) throw RuleFormulaException("out of range")
        val rounded = Math.floor(value + 0.5)
        if (rounded < -9.223372036854775E18 || rounded >= 9.223372036854775E18) throw RuleFormulaException("out of range")
        return rounded.toLong()
    }

    private val balanceOfExpression = Pattern.compile(
        "BALANCE_OF\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)",
        Pattern.CASE_INSENSITIVE or PlatformRegex.unicodeFlag,
    )

    fun extractBalanceOfLiterals(formula: String): List<String> {
        val seen = LinkedHashSet<String>()
        val matcher = balanceOfExpression.matcher(formula)
        while (matcher.find()) seen.add(decodeBalanceOfQuotedLiteral(matcher.group(1)))
        return seen.toList()
    }

    fun decodeBalanceOfQuotedLiteral(inner: String): String =
        inner.replace("\\\"", "\"").replace("\\\\", "\\")
}

private class FormulaParser(formula: String, private val context: RuleFormulaContext) {
    private val chars: CharArray = formula.toCharArray()
    private var index = 0

    fun parseFormula(): RuleFormulaValue {
        skipWhitespace()
        if (peek() != '=') throw RuleFormulaException("missing equals prefix")
        advance()
        return parseExpression()
    }

    fun expectEnd() {
        skipWhitespace()
        if (index < chars.size) throw RuleFormulaException("unsupported")
    }

    private fun parseExpression(): RuleFormulaValue {
        var value = parseTerm()
        while (true) {
            skipWhitespace()
            val op = peek()
            if (op != '+' && op != '-') return value
            advance()
            val rhs = parseTerm()
            value = binary(value, rhs, op)
        }
    }

    private fun parseTerm(): RuleFormulaValue {
        var value = parseUnary()
        while (true) {
            skipWhitespace()
            val op = peek()
            if (op != '*' && op != '/') return value
            advance()
            val rhs = parseUnary()
            value = binary(value, rhs, op)
        }
    }

    private fun parseUnary(): RuleFormulaValue {
        skipWhitespace()
        if (peek() == '-') {
            advance()
            val number = parseUnary().number ?: throw RuleFormulaException("type mismatch")
            return RuleFormulaValue.Num(-number)
        }
        if (peek() == '+') {
            advance()
            return parseUnary()
        }
        return parsePrimary()
    }

    private fun parsePrimary(): RuleFormulaValue {
        skipWhitespace()
        val char = peek() ?: throw RuleFormulaException("unsupported")
        if (char == '(') {
            advance()
            val value = parseExpression()
            skipWhitespace()
            if (peek() != ')') throw RuleFormulaException("unsupported")
            advance()
            return value
        }
        if (char == '"') return RuleFormulaValue.Str(parseString())
        if (char.isDigit() || char == '.') return RuleFormulaValue.Num(parseNumber())
        if (char.isLetter() || char == '_') {
            val name = parseIdentifier()
            skipWhitespace()
            if (peek() == '(') return parseFunction(name)
            return lookup(name)
        }
        throw RuleFormulaException("unsupported")
    }

    private fun parseFunction(rawName: String): RuleFormulaValue {
        advance()
        val arguments = ArrayList<RuleFormulaValue>()
        skipWhitespace()
        if (peek() != ')') {
            arguments.add(parseExpression())
            while (true) {
                skipWhitespace()
                if (peek() != ',') break
                advance()
                arguments.add(parseExpression())
            }
        }
        skipWhitespace()
        if (peek() != ')') throw RuleFormulaException("unsupported")
        advance()
        return evaluateFunction(rawName, arguments)
    }

    private fun evaluateFunction(rawName: String, arguments: List<RuleFormulaValue>): RuleFormulaValue {
        when (rawName.uppercase()) {
            "BALANCE_OF" -> {
                val key = (arguments.singleOrNull() as? RuleFormulaValue.Str)?.value
                    ?: throw RuleFormulaException("type mismatch")
                return RuleFormulaValue.Num((context.balanceOfPrefetch[key] ?: 0L).toDouble())
            }
            "INTEGER_TO_AMOUNT" -> {
                val integer = arguments.firstOrNull()?.number
                if (integer == null || arguments.size !in 1..2) throw RuleFormulaException("type mismatch")
                val decimals = if (arguments.size == 2) (arguments[1].number ?: 2.0) else 2.0
                if (!decimals.isFinite() || decimals < 0 || decimals > 15) throw RuleFormulaException("out of range")
                return RuleFormulaValue.Num(integer / Math.pow(10.0, decimals))
            }
            else -> throw RuleFormulaException("unsupported")
        }
    }

    private fun lookup(name: String): RuleFormulaValue {
        if (name == "today") return RuleFormulaValue.Str(context.today)
        return context.fields[name] ?: RuleFormulaValue.Str("")
    }

    private fun binary(lhs: RuleFormulaValue, rhs: RuleFormulaValue, op: Char?): RuleFormulaValue {
        val left = lhs.number
        val right = rhs.number
        if (left == null || right == null) throw RuleFormulaException("type mismatch")
        return when (op) {
            '+' -> RuleFormulaValue.Num(left + right)
            '-' -> RuleFormulaValue.Num(left - right)
            '*' -> RuleFormulaValue.Num(left * right)
            '/' -> {
                if (right == 0.0) throw RuleFormulaException("divide by zero")
                RuleFormulaValue.Num(left / right)
            }
            else -> throw RuleFormulaException("unsupported")
        }
    }

    private fun parseNumber(): Double {
        val start = index
        while (peek()?.isDigit() == true) advance()
        if (peek() == '.') {
            advance()
            while (peek()?.isDigit() == true) advance()
        }
        val text = String(chars, start, index - start)
        val value = RuleNumbers.parseDouble(text)
        if (value == null || !value.isFinite()) throw RuleFormulaException("unsupported")
        return value
    }

    private fun parseString(): String {
        advance()
        val text = StringBuilder()
        while (true) {
            val char = peek() ?: break
            advance()
            if (char == '"') return text.toString()
            if (char == '\\') {
                val escaped = peek() ?: throw RuleFormulaException("unsupported")
                advance()
                text.append(escaped)
            } else {
                text.append(char)
            }
        }
        throw RuleFormulaException("unsupported")
    }

    private fun parseIdentifier(): String {
        val start = index
        while (true) {
            val char = peek() ?: break
            if (!(char.isLetter() || char.isDigit() || char == '_')) break
            advance()
        }
        return String(chars, start, index - start)
    }

    private fun skipWhitespace() {
        while (peek()?.isWhitespace() == true) advance()
    }

    private fun peek(): Char? = if (index < chars.size) chars[index] else null

    private fun advance() {
        index += 1
    }
}
