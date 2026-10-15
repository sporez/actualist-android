package com.sporez.actualist.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ServerFailoverTest {
    private val primary = "https://home.example"
    private val fallback = "https://away.example"

    private fun unreachable() = ServerException.Unreachable(IOException("down"))

    @Test
    fun onlyUnreachableAndGatewayErrorsFailOver() {
        assertTrue(ServerFailover.isEligible(unreachable()))
        assertTrue(ServerFailover.isEligible(ServerException.HttpStatus(502)))
        assertTrue(ServerFailover.isEligible(ServerException.HttpStatus(504)))
        assertTrue(ServerFailover.isEligible(RuntimeException("wrapped", unreachable())))
        assertFalse(ServerFailover.isEligible(ServerException.HttpStatus(500)))
        assertFalse(ServerFailover.isEligible(ServerException.AuthenticationFailed()))
        assertFalse(ServerFailover.isEligible(ServerException.WrongPassword()))
    }

    @Test
    fun withoutAFallbackItIsAPassThrough() {
        assertThrows(ServerException.Unreachable::class.java) { ServerFailover().run(primary, null) { throw unreachable() } }
        val attempt = ServerFailover().run(primary, null) { it }
        assertEquals(primary, attempt.value)
        assertFalse(attempt.usedFallback)
    }

    @Test
    fun triesTheFallbackWhenThePrimaryIsUnreachableThenSkipsThePrimaryForTheTtl() {
        var clock = 0L
        val failover = ServerFailover(now = { clock }, ttlMillis = 1000)
        val tried = ArrayList<String>()
        val down: (String) -> String = { url ->
            tried += url
            if (url == primary) throw unreachable()
            url
        }
        val first = failover.run(primary, fallback, down)
        assertEquals(fallback, first.value)
        assertTrue(first.usedFallback)
        assertEquals(listOf(primary, fallback), tried)

        tried.clear()
        failover.run(primary, fallback, down)
        assertEquals(listOf(fallback), tried)

        clock = 1001
        tried.clear()
        failover.run(primary, fallback, down)
        assertEquals(listOf(primary, fallback), tried)
    }

    @Test
    fun aRecoveredPrimaryIsUsedAgain() {
        val failover = ServerFailover()
        failover.notePrimaryUnreachable(primary, fallback)
        assertTrue(failover.shouldSkipPrimary(primary, fallback))
        failover.notePrimarySucceeded(primary, fallback)
        val attempt = failover.run(primary, fallback) { it }
        assertEquals(primary, attempt.value)
        assertFalse(attempt.usedFallback)
    }

    @Test
    fun applicationErrorsNeverFailOver() {
        val tried = ArrayList<String>()
        assertThrows(ServerException.AuthenticationFailed::class.java) {
            ServerFailover().run(primary, fallback) { url ->
                tried += url
                throw ServerException.AuthenticationFailed()
            }
        }
        assertEquals(listOf(primary), tried)
    }

    @Test
    fun validatesLikeThePrimary() {
        assertNull(ServerFailover.rejection(""))
        assertNull(ServerFailover.rejection("away.example"))
        assertNull(ServerFailover.rejection("https://away.example/"))
        assertNull(ServerFailover.rejection("http://127.0.0.1:5006"))
        assertNotNull(ServerFailover.rejection("http://away.example"))
        assertNotNull(ServerFailover.rejection("ftp://away.example"))
        assertNotNull(ServerFailover.rejection("https://user:secret@away.example"))
        assertEquals("https://away.example", ServerFailover.normalize(" away.example/ "))
    }

    @Test
    fun aFallbackThatEqualsThePrimaryOrIsInvalidIsIgnored() {
        assertEquals(fallback, ServerFailover.usableFallback(primary, fallback))
        assertNull(ServerFailover.usableFallback(primary, "https://HOME.example/"))
        assertNull(ServerFailover.usableFallback(primary, "http://away.example"))
        assertNull(ServerFailover.usableFallback(primary, null))
        assertNull(ServerFailover.usableFallback(primary, "  "))
    }
}
