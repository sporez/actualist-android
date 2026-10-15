package com.sporez.actualist.core.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Actual's observed account errors, matched exactly on the server's `reason` and `details`
 * (Actualist's `ActualServerErrorCategory`). Unknown server text is never kept.
 */
enum class ServerErrorCategory(val description: String) {
    INVALID_PASSWORD("The server password is incorrect."),
    SESSION_EXPIRED("Your Actual session is no longer valid. Sign in again to resume syncing."),
    PASSWORD_AUTH_DISABLED("Password sign-in is not enabled on this Actual server."),
    INVALID_HEADER("The Actual server rejected an HTTP header."),
    UNKNOWN("The Actual server rejected the request."),
    ;

    companion object {
        fun classify(reason: String?, details: String?): ServerErrorCategory {
            val r = reason?.trim()?.lowercase()
            val d = details?.trim()?.lowercase()
            if (r == "unauthorized" || r == "token-not-found" || d == "token-not-found") return SESSION_EXPIRED
            if (r == "invalid-password") return INVALID_PASSWORD
            if (r == "forbidden" && d == "password-auth-not-active") return PASSWORD_AUTH_DISABLED
            if (r == "invalid-header") return INVALID_HEADER
            return UNKNOWN
        }

        /** Classifies a JSON error body such as `{"status":"error","reason":"forbidden","details":"password-auth-not-active"}`. */
        fun classifyBody(body: String): ServerErrorCategory {
            val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return UNKNOWN
            val data = root["data"] as? JsonObject
            fun text(key: String): String? =
                (root[key] as? JsonPrimitive)?.contentOrNull ?: (data?.get(key) as? JsonPrimitive)?.contentOrNull
            return classify(text("reason"), text("details"))
        }
    }
}
