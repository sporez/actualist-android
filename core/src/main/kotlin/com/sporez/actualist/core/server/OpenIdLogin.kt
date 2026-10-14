package com.sporez.actualist.core.server

import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom

class OpenIdException(message: String) : Exception(message)

/**
 * OpenID sign-in, as Actualist's `ActualOpenIDAuthenticationCoordinator` and
 * `ActualOpenIDCallbackParser` do it: a random nonce names the return address
 * `<scheme>://localhost/openid/<nonce>`; Actual appends `/openid-cb?token=<session token>` after the
 * identity provider, and only that exact path with exactly one non-empty token is accepted.
 */
object OpenIdLogin {
    const val CALLBACK_HOST = "localhost"

    fun makeNonce(random: SecureRandom = SecureRandom()): String =
        ByteArray(32).also { random.nextBytes(it) }.joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 0xFF) }

    fun returnUrl(scheme: String, nonce: String): String = "$scheme://$CALLBACK_HOST/openid/$nonce"

    /** The session token from the redirect back into the app, or an [OpenIdException]. */
    fun token(callback: String, scheme: String, expectedNonce: String): String {
        val uri = try {
            URI(callback)
        } catch (error: Exception) {
            throw OpenIdException("The OpenID sign-in returned an invalid address.")
        }
        if (uri.scheme != scheme || uri.host != CALLBACK_HOST || uri.path != "/openid/$expectedNonce/openid-cb") {
            throw OpenIdException("The OpenID sign-in returned to an unexpected address.")
        }
        val tokens = (uri.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.mapNotNull { pair ->
            val name = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            if (name == "token") URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") else null
        }
        if (tokens.size > 1) throw OpenIdException("The OpenID sign-in response included an ambiguous Actual session token.")
        val token = tokens.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: throw OpenIdException("The OpenID sign-in response did not include an Actual session token.")
        return token
    }
}
