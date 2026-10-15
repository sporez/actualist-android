package com.sporez.actualist.core.server

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Actualist's `ActualServerConnectionSecurity`: plain http is allowed only to a local-network host
 * (loopback, RFC 1918, link-local, `.local`, `.ts.net`, unique-local and link-local IPv6) and is
 * accompanied by a warning. Hosts are classified lexically; a hostname is never resolved, so a
 * DNS answer cannot turn a public name into an allowed one.
 */
object ServerConnectionSecurity {
    const val LOCAL_HTTP_WARNING =
        "This local HTTP connection is unencrypted. Your server password is sent during login, and your " +
            "long-lived sync token is sent with every request. Anyone who can intercept traffic on this " +
            "network can read both. Only continue on a trusted local network."
    const val REMOTE_HTTP_BLOCKED = "HTTP is only allowed for local network Actual servers. Use HTTPS for remote servers."

    /** Whether [host] (a bare host name or address, brackets allowed) is a local-network host. */
    fun isLocalNetworkHost(host: String): Boolean {
        val normalized = host.trim().trim('[', ']').lowercase()
        if (normalized.isEmpty()) return false
        if (normalized == "localhost" || normalized.endsWith(".local") || normalized.endsWith(".ts.net")) return true
        ipv4Octets(normalized)?.let { return isLocalIPv4(it) }
        if (normalized.contains(':')) return isLocalIPv6(normalized.substringBefore('%'))
        return false
    }

    /** The warning for [normalizedUrl] when it is plain http to a local-network host, else null. */
    fun warningFor(normalizedUrl: String): String? {
        val trimmed = normalizedUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true)) return null
        val host = hostOf(trimmed) ?: return null
        return if (isLocalNetworkHost(host)) LOCAL_HTTP_WARNING else null
    }

    private fun hostOf(url: String): String? =
        url.trimEnd('/').toHttpUrlOrNull()?.host

    private fun ipv4Octets(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val value = part.toInt()
            if (value > 255) return null
            octets[i] = value
        }
        return octets
    }

    private fun isLocalIPv4(o: IntArray): Boolean =
        o[0] == 10 || o[0] == 127 ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 169 && o[1] == 254)

    private fun isLocalIPv6(literal: String): Boolean {
        // A literal contains ':', so this never performs a DNS lookup.
        val address = try {
            InetAddress.getByName(literal)
        } catch (_: Exception) {
            return false
        }
        if (address is Inet4Address) {
            val b = address.address
            return isLocalIPv4(IntArray(4) { b[it].toInt() and 0xFF })
        }
        val bytes = address.address
        if (bytes.size != 16) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val loopback = bytes.take(15).all { it.toInt() == 0 } && bytes[15].toInt() == 1
        return loopback || (b0 and 0xFE) == 0xFC || (b0 == 0xFE && (b1 and 0xC0) == 0x80)
    }
}
