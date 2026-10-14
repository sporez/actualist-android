package com.sporez.actualist.core.server

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class OpenIdLoginTest {
    private val scheme = "com.sporez.actualist.android.experimental"
    private val nonce = "ab".repeat(32)

    @Test
    fun acceptsOnlyTheExactCallbackWithOneToken() {
        val base = OpenIdLogin.returnUrl(scheme, nonce)
        assertEquals("$scheme://localhost/openid/$nonce", base)
        assertEquals("tok en", OpenIdLogin.token("$base/openid-cb?token=tok%20en", scheme, nonce))
        assertEquals("t", OpenIdLogin.token("$base/openid-cb?state=x&token=t", scheme, nonce))
        for (bad in listOf(
            "$base/openid-cb",
            "$base/openid-cb?token=",
            "$base/openid-cb?token=a&token=b",
            "$scheme://localhost/openid/${"cd".repeat(32)}/openid-cb?token=t",
            "other://localhost/openid/$nonce/openid-cb?token=t",
            "$scheme://evil.example/openid/$nonce/openid-cb?token=t",
            "$base/elsewhere?token=t",
        )) {
            assertThrows(bad, OpenIdException::class.java) { OpenIdLogin.token(bad, scheme, nonce) }
        }
        val generated = OpenIdLogin.makeNonce()
        assertEquals(64, generated.length)
        assertTrue(generated.all { it in "0123456789abcdef" })
    }

    @Test
    fun beginsTheLoginWithTheReturnAddress() {
        var body = ""
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/account/login") { exchange ->
            body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val reply = """{"status":"ok","data":{"returnUrl":"https://idp.example/authorize?x=1"}}""".toByteArray()
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        http.start()
        try {
            val client = ActualServerClient("http://127.0.0.1:${http.address.port}")
            val url = client.beginOpenIdLogin(OpenIdLogin.returnUrl(scheme, nonce))
            assertEquals("https://idp.example/authorize?x=1", url)
            assertTrue(body.contains("\"loginMethod\":\"openid\""))
            assertTrue(body.contains("\"returnUrl\":\"$scheme://localhost/openid/$nonce\""))
        } finally {
            http.stop(0)
        }
    }
}
