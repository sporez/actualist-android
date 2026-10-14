package com.sporez.actualist.core.banksync.bridge

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64

/** Failures of the device-claim path (setup token and claim). Messages never echo the token, key or host. */
sealed class SimpleFinBridgeException(message: String) : Exception(message) {
    class InvalidSetupToken : SimpleFinBridgeException("That setup token is not valid. Copy the complete token from SimpleFIN and try again.")
    class InsecureUrl : SimpleFinBridgeException("SimpleFIN returned a non-HTTPS address, which is not allowed. Nothing was saved.")
    class AlreadyClaimed : SimpleFinBridgeException("SimpleFIN rejected this setup token, usually because it was already claimed. Disable it in SimpleFIN and create a new one.")
    class InvalidAccessUrl : SimpleFinBridgeException("SimpleFIN returned an unreadable access key.")
    class InvalidResponse : SimpleFinBridgeException("SimpleFIN returned data this app could not read.")
    class UnexpectedStatus(val status: Int) : SimpleFinBridgeException("SimpleFIN returned an unexpected response ($status).")
    class ResponseTooLarge : SimpleFinBridgeException("SimpleFIN returned more data than this app can safely read, so nothing was imported.")
    class RedirectRefused : SimpleFinBridgeException("SimpleFIN redirected the request, which is not allowed.")
    class Unreachable(cause: java.io.IOException) : SimpleFinBridgeException("Could not reach SimpleFIN. Check your connection.") {
        init {
            initCause(cause)
        }
    }

    class TimedOut(cause: java.io.IOException) : SimpleFinBridgeException("SimpleFIN took too long to answer.") {
        init {
            initCause(cause)
        }
    }
}

/**
 * A claimed SimpleFIN access key: the bridge base URL (no credentials) plus the user and password for HTTP Basic auth.
 * [toString] never prints the secret.
 */
class SimpleFinBridgeCredentials(val baseUrl: HttpUrl, val username: String, val password: String) {
    override fun toString(): String = "SimpleFinBridgeCredentials(<redacted>)"
    override fun equals(other: Any?): Boolean =
        other is SimpleFinBridgeCredentials && other.baseUrl == baseUrl && other.username == username && other.password == password

    override fun hashCode(): Int = (baseUrl.hashCode() * 31 + username.hashCode()) * 31 + password.hashCode()

    /** `Authorization` header value; UTF-8 like Actualist, not OkHttp's Latin-1 default. */
    fun basicAuthorization(): String =
        "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
}

/**
 * Parsing and persistence of the access key, ported from Actualist's `SimpleFINBridgeCredentials`.
 *
 * Stored format (identical to what Actualist keeps in its Keychain): the access URL as one string,
 * `https://USER:PASSWORD@HOST[:PORT]/PATH`. It is parsed by hand: everything before the LAST `@` is the credential
 * half, and the FIRST `:` in that half splits user from password, so a password may contain `:` or `@`.
 * The string is a secret; the app keeps it only in its Keystore-backed secret store and never logs it.
 *
 * Only https is accepted, as in Actualist. [allowLoopbackHttp] (off in the app, on in tests) additionally lets
 * plain http through for loopback hosts, the same rule the Actual server client uses.
 */
object SimpleFinBridgeCredentialsCodec {
    private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")

    /** The access URL string to store. */
    fun encode(credentials: SimpleFinBridgeCredentials): String {
        val base = credentials.baseUrl.toString()
        val scheme = credentials.baseUrl.scheme + "://"
        return scheme + credentials.username + ":" + credentials.password + "@" + base.removePrefix(scheme)
    }

    /** Restores credentials from a stored access URL or a claim response body. */
    fun decode(stored: String, allowLoopbackHttp: Boolean = false): SimpleFinBridgeCredentials {
        val body = stored.trim()
        val scheme = schemePrefix(body, allowLoopbackHttp)
        val rest = body.substring(scheme.length)
        val at = rest.lastIndexOf('@')
        if (at < 0) throw SimpleFinBridgeException.InvalidAccessUrl()
        val credentials = rest.substring(0, at)
        val colon = credentials.indexOf(':')
        if (colon < 0) throw SimpleFinBridgeException.InvalidAccessUrl()
        val username = credentials.substring(0, colon)
        val password = credentials.substring(colon + 1)
        if (username.isEmpty() || password.isEmpty()) throw SimpleFinBridgeException.InvalidAccessUrl()
        val base = (scheme + rest.substring(at + 1)).toHttpUrlOrNull() ?: throw SimpleFinBridgeException.InvalidAccessUrl()
        requireSecure(base, allowLoopbackHttp)
        return SimpleFinBridgeCredentials(base, username, password)
    }

    /**
     * A setup token is a (URL-safe) Base64-encoded claim URL. Returns that URL; refuses anything that is not
     * https (or loopback http when [allowLoopbackHttp]).
     */
    fun claimUrl(setupToken: String, allowLoopbackHttp: Boolean = false): HttpUrl {
        val token = setupToken.trim().replace('-', '+').replace('_', '/')
        val padded = token + "=".repeat((4 - token.length % 4) % 4)
        val decoded = try {
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            throw SimpleFinBridgeException.InvalidSetupToken()
        }
        val url = decoded.trim().toHttpUrlOrNull() ?: throw SimpleFinBridgeException.InvalidSetupToken()
        requireSecure(url, allowLoopbackHttp)
        return url
    }

    internal fun requireSecure(url: HttpUrl, allowLoopbackHttp: Boolean) {
        if (url.isHttps) return
        if (allowLoopbackHttp && url.host in loopbackHosts) return
        throw SimpleFinBridgeException.InsecureUrl()
    }

    private fun schemePrefix(body: String, allowLoopbackHttp: Boolean): String {
        val lower = body.lowercase()
        if (lower.startsWith("https://")) return body.substring(0, "https://".length)
        if (allowLoopbackHttp && lower.startsWith("http://")) return body.substring(0, "http://".length)
        throw SimpleFinBridgeException.InsecureUrl()
    }
}
