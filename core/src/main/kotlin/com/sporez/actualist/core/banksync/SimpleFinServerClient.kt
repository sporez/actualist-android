package com.sporez.actualist.core.banksync

import com.sporez.actualist.core.server.ActualServerClient
import com.sporez.actualist.core.server.ServerException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException

/** The SimpleFIN transport seam; tests and the facade depend on this, production uses [SimpleFinServerClient]. */
interface SimpleFinTransport {
    /** `POST /simplefin/status`. */
    fun status(token: String): SimpleFinServerSupport

    /** `POST /simplefin/accounts`; null when the server does not host the SimpleFIN routes. */
    fun accounts(token: String): List<SimpleFinRemoteAccount>?

    /** `POST /simplefin/transactions`; [accountIds] and [startDates] are positional. Null when unsupported. */
    fun transactions(token: String, accountIds: List<String>, startDates: List<String>): SimpleFinTransactionsResponse?
}

/**
 * Talks to SimpleFIN through the Actual server's the `simplefin` routes routes: the server holds the SimpleFIN setup
 * token and the bridge access key, the app only presents its Actual session token (`X-ACTUAL-TOKEN`). Port of
 * Actualist's `ActualServerSimpleFINClient`: JSON POST bodies, `Accept: application/json`, redirects refused,
 * plain http accepted only for loopback, responses capped at 16 MiB. A server that answers 404, 405 or 501 has
 * no SimpleFIN routes, which is "cannot sync" (a null result), not an error.
 */
class SimpleFinServerClient(
    serverUrl: String,
    private val http: OkHttpClient = ActualServerClient.defaultHttpClient(),
    private val maximumResponseBytes: Long = 16L * 1024 * 1024,
) : SimpleFinTransport {
    val baseUrl: HttpUrl = try {
        ActualServerClient.validatedUrl(serverUrl)
    } catch (error: ServerException.InsecureUrl) {
        throw SimpleFinException.InsecureUrl()
    } catch (error: ServerException) {
        throw SimpleFinException.InvalidUrl()
    }

    override fun status(token: String): SimpleFinServerSupport {
        val text = post("simplefin/status", token, "{}") ?: return SimpleFinServerSupport.UNSUPPORTED
        return if (SimpleFinWire.configured(SimpleFinWire.parseRoot(text))) SimpleFinServerSupport.CONFIGURED else SimpleFinServerSupport.NOT_CONFIGURED
    }

    override fun accounts(token: String): List<SimpleFinRemoteAccount>? {
        val text = post("simplefin/accounts", token, "{}") ?: return null
        return SimpleFinWire.accounts(SimpleFinWire.parseRoot(text))
    }

    override fun transactions(token: String, accountIds: List<String>, startDates: List<String>): SimpleFinTransactionsResponse? {
        val body = JsonObject(
            mapOf(
                "accountId" to JsonArray(accountIds.map { JsonPrimitive(it) }),
                "startDate" to JsonArray(startDates.map { JsonPrimitive(it) }),
            ),
        ).toString()
        val text = post("simplefin/transactions", token, body) ?: return null
        return SimpleFinWire.transactions(SimpleFinWire.parseRoot(text))
    }

    /** The response body, or null when the route is unsupported (404, 405, 501). */
    private fun post(path: String, token: String, body: String): String? {
        val url = baseUrl.newBuilder().addPathSegments(path).build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header(ActualServerClient.TOKEN, token)
            .post(body.toRequestBody(JSON))
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (error: SocketTimeoutException) {
            throw SimpleFinException.TimedOut(error)
        } catch (error: IOException) {
            throw SimpleFinException.Unreachable(error)
        }
        response.use {
            if (it.isRedirect) throw SimpleFinException.RedirectRefused()
            val code = it.code
            if (code == 404 || code == 405 || code == 501) return null
            val declared = it.body.contentLength()
            if (declared > maximumResponseBytes) throw SimpleFinException.ResponseTooLarge()
            val bytes = try {
                val source = it.body.source()
                // `request` buffers until it has the bytes or the body ends: true means over the limit.
                if (source.request(maximumResponseBytes + 1)) throw SimpleFinException.ResponseTooLarge()
                source.readByteArray()
            } catch (error: SocketTimeoutException) {
                throw SimpleFinException.TimedOut(error)
            } catch (error: IOException) {
                throw SimpleFinException.Unreachable(error)
            }
            val text = String(bytes, Charsets.UTF_8)
            if (code !in 200..299) throw failure(code, text)
            envelopeError(text, null)?.let { error -> throw error }
            return text
        }
    }

    /** Actual's `{status: "error", reason, details}` envelope, which can ride on a 2xx too. */
    private fun envelopeError(text: String, status: Int?): SimpleFinException? {
        val root = try {
            SimpleFinWire.parseRoot(text)
        } catch (error: SimpleFinException) {
            return null
        }
        val isError = (root["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase() == "error"
        val reason = (root["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val details = (root["details"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!isError && status == null) return null
        if (reason == null && details == null && !isError) return null
        val normalized = reason?.trim()?.lowercase()
        if (normalized == "unauthorized" || normalized == "token-not-found" || details?.trim()?.lowercase() == "token-not-found") {
            return SimpleFinException.AuthenticationFailed()
        }
        return SimpleFinException.ServerRejected(status, reason)
    }

    private fun failure(status: Int, text: String): SimpleFinException {
        envelopeError(text, status)?.let { return it }
        if (status == 401 || status == 403) return SimpleFinException.AuthenticationFailed()
        return SimpleFinException.HttpStatus(status)
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
