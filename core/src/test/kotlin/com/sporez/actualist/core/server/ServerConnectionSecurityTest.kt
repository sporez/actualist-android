package com.sporez.actualist.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConnectionSecurityTest {
    @Test
    fun privateLoopbackAndLinkLocalIPv4AreLocal() {
        for (host in listOf("10.0.0.5", "127.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.10", "169.254.3.4")) {
            assertTrue(host, ServerConnectionSecurity.isLocalNetworkHost(host))
        }
    }

    @Test
    fun publicAndBoundaryIPv4AreNotLocal() {
        for (host in listOf("172.15.0.1", "172.32.0.1", "192.169.1.1", "192.0.2.10", "8.8.8.8", "100.64.0.1", "11.0.0.1", "1.2.3", "300.1.1.1")) {
            assertFalse(host, ServerConnectionSecurity.isLocalNetworkHost(host))
        }
    }

    @Test
    fun localHostNamesAreLocalAndOtherNamesAreNot() {
        for (host in listOf("localhost", "nas.local", "NAS.LOCAL", "box.tailnet.ts.net")) {
            assertTrue(host, ServerConnectionSecurity.isLocalNetworkHost(host))
        }
        for (host in listOf("budget.example.com", "local", "mylocal.com", "nas", "")) {
            assertFalse(host, ServerConnectionSecurity.isLocalNetworkHost(host))
        }
    }

    @Test
    fun ipv6LoopbackUniqueLocalLinkLocalAndMappedPrivateAreLocal() {
        for (host in listOf("::1", "[::1]", "fd12:3456::1", "fc00::1", "fe80::1", "::ffff:192.168.1.5")) {
            assertTrue(host, ServerConnectionSecurity.isLocalNetworkHost(host))
        }
        for (host in listOf("2001:db8::1", "::ffff:8.8.8.8", "fec0::1")) {
            assertFalse(host, ServerConnectionSecurity.isLocalNetworkHost(host))
        }
    }

    @Test
    fun warningOnlyForPlainHttpToLocalHosts() {
        assertEquals(ServerConnectionSecurity.LOCAL_HTTP_WARNING, ServerConnectionSecurity.warningFor("http://192.168.1.10:5006"))
        assertNotNull(ServerConnectionSecurity.warningFor("http://nas.local"))
        assertNull(ServerConnectionSecurity.warningFor("https://192.168.1.10:5006"))
        assertNull(ServerConnectionSecurity.warningFor("http://budget.example.com"))
        assertNull(ServerConnectionSecurity.warningFor("not a url"))
    }

    @Test
    fun clientAcceptsLocalHttpAndRefusesPublicHttp() {
        ActualServerClient("http://192.168.1.10:5006")
        ActualServerClient("http://nas.local:5006")
        assertThrows(ServerException.InsecureUrl::class.java) { ActualServerClient("http://budget.example.com") }
        assertThrows(ServerException.InsecureUrl::class.java) { ActualServerClient("http://203.0.113.9:5006") }
        assertNull(ServerFailover.rejection("http://192.168.1.10:5006"))
        assertEquals(ServerConnectionSecurity.REMOTE_HTTP_BLOCKED, ServerFailover.rejection("http://203.0.113.9"))
    }
}
