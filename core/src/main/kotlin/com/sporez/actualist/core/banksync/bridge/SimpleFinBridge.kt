package com.sporez.actualist.core.banksync.bridge

import com.sporez.actualist.core.banksync.SimpleFinException
import com.sporez.actualist.core.banksync.SimpleFinServerSupport
import com.sporez.actualist.core.banksync.SimpleFinTransport

/**
 * The app-facing side of the direct bridge, in plain strings: claim a setup token into the stored
 * access key, and choose the transport the way Actualist's `bankSyncProvider` does.
 */
object SimpleFinBridge {
    /** Claims [setupToken] once and returns the access key string to keep in the secret store. */
    fun claimToStoredKey(setupToken: String): String = SimpleFinBridgeCredentialsCodec.encode(SimpleFinBridgeClaim.claim(setupToken.trim()))

    /**
     * The server when it hosts a configured SimpleFIN; otherwise (or when the server cannot be
     * reached) the device's own bridge key, if one is stored. Without a key the server transport is
     * returned and reports "not configured" as before.
     */
    fun choose(server: SimpleFinTransport, sessionToken: String, storedBridgeKey: String?): SimpleFinTransport {
        if (storedBridgeKey == null) return server
        val support = try {
            server.status(sessionToken)
        } catch (error: SimpleFinException.Unreachable) {
            null
        } catch (error: SimpleFinException.TimedOut) {
            null
        }
        return if (support == SimpleFinServerSupport.CONFIGURED) server else SimpleFinBridgeTransport(SimpleFinBridgeCredentialsCodec.decode(storedBridgeKey))
    }
}
