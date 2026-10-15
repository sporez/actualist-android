package com.sporez.actualist.core.server

/**
 * Removes anything private from a message before it enters the diagnostic report (Actualist's
 * `DiagnosticReportRedactor`): the known sensitive values (server address, host, budget and file
 * ids, names), addresses, ids, emails, credentials, file paths and amounts.
 */
class DiagnosticRedactor(sensitiveValues: List<String>, credentials: List<String> = emptyList()) {
    private val values: List<String> = (
        sensitiveValues.map { it.trim() }.filter { it.length >= 4 } + credentials.filter { it.isNotEmpty() }
        ).distinct().sortedByDescending { it.length }

    fun redact(message: String?): String {
        var result = message?.trim()
        if (result.isNullOrEmpty()) return "none"
        for (value in values) result = result!!.replace(value, "[redacted]", ignoreCase = true)
        var text = result!!
        text = URL.replace(text, "[redacted-url]")
        text = UUID.replace(text, "[redacted-id]")
        text = ADDRESS.replace(text, "[redacted-address]")
        text = EMAIL.replace(text, "[redacted-email]")
        text = SECRET.replace(text) { match -> match.groupValues[1] + "=[redacted]" }
        text = PATH.replace(text, "[redacted-path]")
        text = AMOUNT.replace(text, "[redacted-amount]")
        return text.replace("\r\n", " ").replace("\n", " ").replace("\r", " ")
    }

    private companion object {
        val URL = Regex("""https?://\S+""")
        val UUID = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\b""")
        val ADDRESS = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
        val EMAIL = Regex("""\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""", RegexOption.IGNORE_CASE)
        val SECRET = Regex("""(?i)\b(password|token|secret|encryption[-_ ]?key)\s*[:=]\s*[^\s,;]+""")
        val PATH = Regex("""(?:file://)?/(?:[^\s/:]+/)+[^\s:]+""")
        val AMOUNT = Regex("""(?:[$€£¥]\s*[-+]?\d[\d,.]*|[-+]?\d[\d,.]*\s*(?:USD|EUR|GBP|JPY))""", RegexOption.IGNORE_CASE)
    }
}
