package com.sporez.actualist.core.banksync.bridge

import com.sporez.actualist.core.server.ActualServerClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * One-time claim of a SimpleFIN setup token (Actualist `SimpleFINBridgeClient.claim`): POST an empty body to the
 * decoded claim URL; the 200 reply body is the access URL with embedded credentials. HTTP 403, or a 200 whose body
 * starts `Forbidden`, means the token was already claimed. Redirects are refused; the reply is capped at 64 KiB.
 * The setup token and access key are never logged or placed in error messages.
 */
object SimpleFinBridgeClaim {
    private const val MAXIMUM_CLAIM_BYTES = 64L * 1024

    fun claim(
        setupToken: String,
        http: OkHttpClient = ActualServerClient.defaultHttpClient(),
        allowLoopbackHttp: Boolean = false,
    ): SimpleFinBridgeCredentials {
        val claimUrl = SimpleFinBridgeCredentialsCodec.claimUrl(setupToken, allowLoopbackHttp)
        val noRedirects = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val request = Request.Builder()
            .url(claimUrl)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val response = try {
            noRedirects.newCall(request).execute()
        } catch (error: SocketTimeoutException) {
            throw SimpleFinBridgeException.TimedOut(error)
        } catch (error: IOException) {
            throw SimpleFinBridgeException.Unreachable(error)
        }
        response.use {
            if (it.isRedirect) throw SimpleFinBridgeException.RedirectRefused()
            if (it.code == 403) throw SimpleFinBridgeException.AlreadyClaimed()
            if (it.code != 200) throw SimpleFinBridgeException.UnexpectedStatus(it.code)
            if (it.body.contentLength() > MAXIMUM_CLAIM_BYTES) throw SimpleFinBridgeException.ResponseTooLarge()
            val bytes = try {
                val source = it.body.source()
                if (source.request(MAXIMUM_CLAIM_BYTES + 1)) throw SimpleFinBridgeException.ResponseTooLarge()
                source.readByteArray()
            } catch (error: SocketTimeoutException) {
                throw SimpleFinBridgeException.TimedOut(error)
            } catch (error: IOException) {
                throw SimpleFinBridgeException.Unreachable(error)
            }
            val body = String(bytes, Charsets.UTF_8)
            // Some bridges answer 200 with a plain-text rejection.
            if (body.trim().lowercase().startsWith("forbidden")) throw SimpleFinBridgeException.AlreadyClaimed()
            return SimpleFinBridgeCredentialsCodec.decode(body, allowLoopbackHttp)
        }
    }
}
