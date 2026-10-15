package com.sporez.actualist.core.server

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Actualist's fallback server rules: a second address for the same server (for example a VPN
 * address when away from home), tried when the primary cannot be reached at all.
 *
 * Only "could not reach the server" failures fail over: no connection, no host, timeout, TLS
 * handshake failure, or a gateway status (502, 503, 504) from a proxy whose upstream is down.
 * Authentication failures and other application errors never do. After a successful failover the
 * primary is remembered as down for [ttlMillis] (15 minutes), so later calls go straight to the
 * fallback instead of waiting for the primary to time out again.
 */
class ServerFailover(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val ttlMillis: Long = 15L * 60 * 1000,
) {
    /** The result of [run] and which endpoint produced it. */
    class Attempt<T>(val value: T, val usedFallback: Boolean)

    private val downUntil = HashMap<String, Long>()

    private fun key(primary: String, fallback: String) = "$primary\u0000$fallback"

    @Synchronized
    fun shouldSkipPrimary(primary: String, fallback: String): Boolean = (downUntil[key(primary, fallback)] ?: return false) > now()

    @Synchronized
    fun notePrimaryUnreachable(primary: String, fallback: String) {
        downUntil[key(primary, fallback)] = now() + ttlMillis
    }

    @Synchronized
    fun notePrimarySucceeded(primary: String, fallback: String) {
        downUntil.remove(key(primary, fallback))
    }

    @Synchronized
    fun clear() = downUntil.clear()

    /**
     * Runs [operation] against [primary], then against [fallback] when the primary is unreachable.
     * With no fallback it is a straight pass-through. [operation] receives the server address to use.
     */
    fun <T> run(primary: String, fallback: String?, operation: (String) -> T): Attempt<T> {
        if (fallback == null) return Attempt(operation(primary), false)
        if (shouldSkipPrimary(primary, fallback)) {
            try {
                return Attempt(operation(fallback), true)
            } catch (error: Exception) {
                // The fallback failed the same way: try the primary again next time.
                if (isEligible(error)) notePrimarySucceeded(primary, fallback)
                throw error
            }
        }
        try {
            val value = operation(primary)
            notePrimarySucceeded(primary, fallback)
            return Attempt(value, false)
        } catch (error: Exception) {
            if (!isEligible(error)) throw error
        }
        val value = operation(fallback)
        notePrimaryUnreachable(primary, fallback)
        return Attempt(value, true)
    }

    companion object {
        val gatewayStatuses = setOf(502, 503, 504)

        /** Whether [error] means the server could not be reached at all (the only case that fails over). */
        fun isEligible(error: Throwable): Boolean = generateSequence(error) { it.cause }.any { cause ->
            cause is ServerException.Unreachable || (cause is ServerException.HttpStatus && cause.status in gatewayStatuses)
        }

        /** Adds `https://` when no scheme was typed, and drops a trailing slash. Blank stays blank. */
        fun normalize(input: String): String {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return ""
            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            return withScheme.trimEnd('/')
        }

        /**
         * A user-facing reason the address cannot be used (as for the primary: https, or http only
         * for this device), or null when it is acceptable. Blank is not a rejection (it clears the field).
         */
        fun rejection(input: String): String? {
            val normalized = normalize(input)
            if (normalized.isEmpty()) return null
            val parsed = normalized.toHttpUrlOrNull()
            if (parsed != null && (parsed.username.isNotEmpty() || parsed.password.isNotEmpty())) {
                return "Server addresses can't include a username or password. Remove it from the address."
            }
            return try {
                ActualServerClient.validatedUrl(normalized)
                null
            } catch (error: ServerException) {
                error.message ?: "The server URL is invalid."
            }
        }

        /** The fallback to use for [primary]: valid, and not the same address; otherwise null. */
        fun usableFallback(primary: String?, fallback: String?): String? {
            val candidate = normalize(fallback.orEmpty())
            if (candidate.isEmpty() || rejection(candidate) != null) return null
            val same = primary?.let { normalize(it) }
            if (same != null && same.equals(candidate, ignoreCase = true)) return null
            return candidate
        }
    }
}
