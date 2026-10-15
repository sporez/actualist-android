package com.sporez.actualist.core.server

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerErrorCategoryTest {
    @Test
    fun classifiesObservedAccountErrorsExactly() {
        assertEquals(ServerErrorCategory.SESSION_EXPIRED, ServerErrorCategory.classify("unauthorized", null))
        assertEquals(ServerErrorCategory.SESSION_EXPIRED, ServerErrorCategory.classify("token-not-found", null))
        assertEquals(ServerErrorCategory.SESSION_EXPIRED, ServerErrorCategory.classify("x", " Token-Not-Found "))
        assertEquals(ServerErrorCategory.INVALID_PASSWORD, ServerErrorCategory.classify("invalid-password", null))
        assertEquals(ServerErrorCategory.PASSWORD_AUTH_DISABLED, ServerErrorCategory.classify("forbidden", "password-auth-not-active"))
        assertEquals(ServerErrorCategory.UNKNOWN, ServerErrorCategory.classify("forbidden", null))
        assertEquals(ServerErrorCategory.INVALID_HEADER, ServerErrorCategory.classify("invalid-header", null))
        assertEquals(ServerErrorCategory.UNKNOWN, ServerErrorCategory.classify(null, null))
    }

    @Test
    fun messagesMatchActualist() {
        assertEquals("Password sign-in is not enabled on this Actual server.", ServerErrorCategory.PASSWORD_AUTH_DISABLED.description)
        assertEquals("The Actual server rejected an HTTP header.", ServerErrorCategory.INVALID_HEADER.description)
        assertEquals("The Actual server rejected the request.", ServerErrorCategory.UNKNOWN.description)
        assertEquals("The Actual server rejected an HTTP header.", ServerException.HeaderRejected().message)
        assertEquals("Password sign-in is not enabled on this Actual server.", ServerException.PasswordSignInDisabled().message)
    }

    @Test
    fun classifiesJsonBodies() {
        val disabled = """{"status":"error","reason":"forbidden","details":"password-auth-not-active"}"""
        assertEquals(ServerErrorCategory.PASSWORD_AUTH_DISABLED, ServerErrorCategory.classifyBody(disabled))
        assertEquals(ServerErrorCategory.INVALID_HEADER, ServerErrorCategory.classifyBody("""{"status":"error","reason":"invalid-header"}"""))
        assertEquals(ServerErrorCategory.UNKNOWN, ServerErrorCategory.classifyBody("not json"))
    }
}
