package com.sporez.actualist.core.server

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class CustomHeadersTest {
    @Test
    fun validatesAsActualistDoes() {
        val parsed = EndpointHeaders.parse("CF-Access-Client-Id: synthetic-id\n\nCF-Access-Client-Secret:  s3cret ")
        assertEquals(listOf("CF-Access-Client-Id", "CF-Access-Client-Secret"), parsed.map { it.name })
        assertEquals("s3cret", parsed[1].value)
        assertFalse(parsed.toString().contains("s3cret"))
        for (bad in listOf("X-ACTUAL-TOKEN: x", "Bad Name: x", "A: 1\na: 2", "Host: x")) {
            assertThrows(bad, CustomHeaderException::class.java) { EndpointHeaders("https://budget.example", EndpointHeaders.parse(bad)) }
        }
        assertThrows(CustomHeaderException::class.java) { EndpointHeaders("https://budget.example", listOf(CustomHeader("X-Ok", "line\nbreak"))) }
        assertThrows(CustomHeaderException::class.java) { EndpointHeaders.parse("no colon here") }
    }

    @Test
    fun sendsHeadersOnlyToTheirOrigin() {
        val seen = ArrayList<String?>()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            seen += exchange.requestHeaders.getFirst("X-Proxy-Key")
            val reply = """{"status":"ok","methods":[{"method":"password","active":1}]}""".toByteArray()
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        http.start()
        try {
            val url = "http://127.0.0.1:${http.address.port}"
            val endpoint = EndpointHeaders(url, listOf(CustomHeader("X-Proxy-Key", "synthetic")))
            assertTrue(ActualServerClient(url, httpClientWithHeaders(endpoint)).loginMethods().contains("password"))
            // Saved for another port: not sent here.
            val other = EndpointHeaders("http://127.0.0.1:${http.address.port + 1}", listOf(CustomHeader("X-Proxy-Key", "synthetic")))
            ActualServerClient(url, httpClientWithHeaders(other)).loginMethods()
            assertEquals("synthetic", seen[0])
            assertNull(seen[1])
        } finally {
            http.stop(0)
        }
    }
}
