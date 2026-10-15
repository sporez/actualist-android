package com.sporez.actualist.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticRedactorTest {
    private val redactor = DiagnosticRedactor(listOf("budget.home.example", "Family Budget", "abc"), listOf("secret-header-value"))

    @Test
    fun emptyMessagesReadNone() {
        assertEquals("none", redactor.redact(null))
        assertEquals("none", redactor.redact("   "))
    }

    @Test
    fun removesHostsUrlsIdsAddressesAndPaths() {
        val text = redactor.redact("Failed https://budget.home.example/sync for Family Budget at 192.168.1.20, file /data/user/0/app/databases/x.sqlite id 123e4567-e89b-42d3-a456-426614174000")
        assertFalse(text.contains("home.example"))
        assertFalse(text.contains("Family"))
        assertFalse(text.contains("192.168"))
        assertFalse(text.contains("/data/user"))
        assertFalse(text.contains("123e4567"))
        assertEquals(true, text.contains("[redacted-url]"))
    }

    @Test
    fun removesCredentialsEmailsAndAmounts() {
        val text = redactor.redact("token=abc123 sent secret-header-value to a@b.com balance $12.50\nnext")
        assertFalse(text.contains("abc123"))
        assertFalse(text.contains("secret-header-value"))
        assertFalse(text.contains("a@b.com"))
        assertFalse(text.contains("12.50"))
        assertFalse(text.contains("\n"))
    }

    @Test
    fun shortSensitiveValuesAreIgnored() {
        assertEquals("abc stays", redactor.redact("abc stays"))
    }
}
