package com.sporez.actualist.android.sync

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.sporez.actualist.android.data.AppSettings
import com.sporez.actualist.android.data.BudgetLibrary
import com.sporez.actualist.android.data.LocalBudget
import com.sporez.actualist.android.data.SecretStore
import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.server.ActualServerClient
import com.sporez.actualist.core.server.BudgetImporter
import com.sporez.actualist.core.server.RemoteFile
import com.sporez.actualist.core.server.ServerException
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.store.SyncStatus
import com.sporez.actualist.core.sync.EncryptionContext
import com.sporez.actualist.core.sync.HybridLogicalClock
import com.sporez.actualist.core.sync.SyncConfiguration
import com.sporez.actualist.core.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Server sign-in, budget download and sync for the app. The session token and derived
 * budget keys are kept only in [SecretStore]. Sync status is published to the session and
 * reports success only after [SyncEngine] returned (server confirmed, merkle matched).
 */
class ServerConnection(
    private val settings: AppSettings,
    private val secrets: SecretStore,
    private val library: BudgetLibrary,
) {
    private val driver = BundledSQLiteDriver()

    val isSignedIn: Boolean get() = settings.settings.value.serverUrl != null && secrets.getString(SecretStore.SERVER_TOKEN) != null

    private fun client(url: String = settings.settings.value.serverUrl ?: throw ServerException.InvalidUrl()) =
        com.sporez.actualist.core.server.ServerClients.actual(url, customHeaders(url))

    /** The saved custom headers, as `Name: value` lines, and the server they belong to. */
    fun savedCustomHeaders(): Pair<String, String>? =
        secrets.getString(CUSTOM_HEADERS)?.let { it.substringBefore('\u0000') to it.substringAfter('\u0000') }

    /** Headers for [url]'s origin only (Actualist scopes them to the server they were saved for). */
    private fun customHeaders(url: String): com.sporez.actualist.core.server.EndpointHeaders? {
        val (savedUrl, text) = savedCustomHeaders() ?: return null
        val endpoint = runCatching { com.sporez.actualist.core.server.EndpointHeaders(savedUrl, com.sporez.actualist.core.server.EndpointHeaders.parse(text)) }.getOrNull()
        return endpoint?.takeIf { it.appliesToUrl(url) }
    }

    /** Validates and stores custom headers for [serverUrl] (blank removes them). Throws with a readable reason. */
    fun saveCustomHeaders(serverUrl: String, text: String) {
        if (text.isBlank()) {
            secrets.remove(CUSTOM_HEADERS)
            return
        }
        val url = ActualServerClient(serverUrl).url
        com.sporez.actualist.core.server.EndpointHeaders(url, com.sporez.actualist.core.server.EndpointHeaders.parse(text))
        secrets.putString(CUSTOM_HEADERS, url + "\u0000" + text.trim())
    }

    private fun token(): String = secrets.getString(SecretStore.SERVER_TOKEN) ?: throw ServerException.AuthenticationFailed()

    /** The active login methods [serverUrl] advertises (`password`, `openid`, `header`). */
    suspend fun loginMethods(serverUrl: String): List<String> = withContext(Dispatchers.IO) { client(serverUrl).loginMethods() }

    suspend fun signIn(serverUrl: String, password: String) = withContext(Dispatchers.IO) {
        val client = client(serverUrl)
        val methods = client.loginMethods()
        if ("password" !in methods) throw ServerException.HttpStatus(501)
        val token = client.login(password)
        secrets.putString(SecretStore.SERVER_TOKEN, token)
        settings.update { it.copy(serverUrl = client.url) }
    }

    sealed class OpenIdOutcome {
        data object SignedIn : OpenIdOutcome()
        data class Failed(val message: String) : OpenIdOutcome()
    }

    private val _openId = kotlinx.coroutines.flow.MutableStateFlow<OpenIdOutcome?>(null)
    /** The result of the last OpenID redirect back into the app, until [consumeOpenId]. */
    val openId: kotlinx.coroutines.flow.StateFlow<OpenIdOutcome?> = _openId

    /**
     * Starts OpenID sign-in as Actualist does and returns the identity provider address to open
     * in a browser. The nonce and server are kept until the redirect comes back (also across a
     * process restart).
     */
    suspend fun beginOpenId(serverUrl: String, scheme: String): String = withContext(Dispatchers.IO) {
        val client = client(serverUrl)
        if ("openid" !in client.loginMethods()) throw com.sporez.actualist.core.server.OpenIdException("This server does not offer OpenID sign-in.")
        val nonce = com.sporez.actualist.core.server.OpenIdLogin.makeNonce()
        val authorize = client.beginOpenIdLogin(com.sporez.actualist.core.server.OpenIdLogin.returnUrl(scheme, nonce))
        secrets.putString(OPENID_PENDING, "$nonce ${client.url}")
        authorize
    }

    /** Handles `<scheme>://localhost/openid/<nonce>/openid-cb?token=...`; only the pending nonce is accepted. */
    fun completeOpenId(callback: String, scheme: String) {
        val pending = secrets.getString(OPENID_PENDING)
        if (pending == null) {
            _openId.value = OpenIdOutcome.Failed("No OpenID sign-in is in progress.")
            return
        }
        secrets.remove(OPENID_PENDING)
        val nonce = pending.substringBefore(' ')
        val url = pending.substringAfter(' ')
        _openId.value = try {
            val token = com.sporez.actualist.core.server.OpenIdLogin.token(callback, scheme, nonce)
            secrets.putString(SecretStore.SERVER_TOKEN, token)
            settings.update { it.copy(serverUrl = url) }
            OpenIdOutcome.SignedIn
        } catch (error: com.sporez.actualist.core.server.OpenIdException) {
            OpenIdOutcome.Failed(error.message ?: "OpenID sign-in failed.")
        }
    }

    fun consumeOpenId() {
        _openId.value = null
    }

    fun signOut() {
        secrets.remove(SecretStore.SERVER_TOKEN)
        settings.update { it.copy(serverUrl = null) }
    }

    suspend fun remoteBudgets(): List<RemoteFile> = withContext(Dispatchers.IO) { client().listUserFiles(token()) }

    /**
     * Downloads, decrypts and installs a budget with a fresh node id, as Actualist does for
     * every download. [password] is required when the budget is encrypted.
     */
    suspend fun download(remote: RemoteFile, password: String?): LocalBudget = withContext(Dispatchers.IO) {
        val client = client()
        val token = token()
        val encryption: EncryptionContext? = if (remote.encryptKeyId != null) {
            val secret = password ?: throw LocalFirstException.EncryptedBudgetRequiresPassword()
            BudgetImporter.validateUserKey(client.userKey(token, remote.fileId), secret)
        } else {
            null
        }
        val bytes = client.downloadUserFile(token, remote.fileId)
        val zip = BudgetImporter.decryptIfNeeded(bytes, remote, encryption)
        val id = remote.fileId
        BudgetImporter.install(zip, library.databaseFile(id), driver)
        encryption?.let { secrets.put(SecretStore.budgetKey(id), it.keyData) }
        val metadata = LocalBudget(
            id = id,
            name = remote.name,
            isDemo = false,
            cloudFileId = remote.fileId,
            groupId = remote.groupId,
            encryptKeyId = remote.encryptKeyId,
            nodeId = HybridLogicalClock.makeClientId(),
        )
        library.saveMetadata(metadata)
        metadata
    }

    /**
     * Creates a new budget on the server, as Actualist's `createNewBudget` does: a starter file is
     * built locally, registered with `upload-user-file`, and kept only once the server confirmed
     * it. Any failure removes the local copy, so nothing unregistered stays selectable.
     */
    suspend fun createBudget(name: String, encryptionPassword: String? = null, importArchive: ByteArray? = null): LocalBudget = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw LocalFirstException.InvalidLocalWrite("Enter a name for the new budget.")
        val client = client()
        val token = token()
        val id = java.util.UUID.randomUUID().toString()
        val file = library.databaseFile(id)
        var uploaded = false
        try {
            if (importArchive == null) {
                com.sporez.actualist.core.server.NewBudget.createFile(driver, file.absolutePath)
            } else {
                // Import a budget ZIP as a new budget: sanitized like a download, with Actual's
                // resetSync so the new server group starts from an empty history.
                com.sporez.actualist.core.server.BudgetImporter.install(importArchive, file, driver)
                com.sporez.actualist.core.server.PortableBudget.resetSyncHistory(file, driver)
            }
            val archive = com.sporez.actualist.core.server.NewBudget.archive(file, id, trimmed)
            val keys = encryptionPassword?.takeIf { it.isNotEmpty() }?.let { com.sporez.actualist.core.server.NewBudget.RegistrationKeys.make(it, archive) }
            val groupId = client.uploadUserFile(token, id, trimmed, null, keys?.encryptedArchive ?: archive, keys?.encryptMeta)
                ?: client.userFileInfo(token, id)?.groupId
                ?: throw ServerException.InvalidResponse()
            uploaded = true
            // Upload first, then the key, as Actualist's registration flow does.
            keys?.let { client.createUserKey(token, id, it.keyId, it.salt, it.testContent) }
            keys?.let { secrets.put(SecretStore.budgetKey(id), it.keyData) }
            val metadata = LocalBudget(
                id = id, name = trimmed, isDemo = false, cloudFileId = id, groupId = groupId,
                encryptKeyId = keys?.keyId, nodeId = HybridLogicalClock.makeClientId(),
            )
            library.saveMetadata(metadata)
            metadata
        } catch (error: Exception) {
            // A landed file without its key would stay undecryptable on the server: withdraw it.
            if (uploaded) runCatching { client.deleteUserFile(token, id) }
            library.delete(id)
            throw error
        }
    }

    /** Imports a budget ZIP (Actual's or this app's export) as a new budget on this server. */
    suspend fun importBudget(zip: ByteArray, name: String, encryptionPassword: String? = null): LocalBudget =
        createBudget(name, encryptionPassword, importArchive = zip)

    /** SimpleFIN bank sync for [database] through this server, which holds the SimpleFIN token (as in Actualist). */
    fun simpleFin(database: com.sporez.actualist.core.db.BudgetDatabase, clock: com.sporez.actualist.core.db.Clock): com.sporez.actualist.core.banksync.SimpleFinSync {
        val url = settings.settings.value.serverUrl ?: throw ServerException.InvalidUrl()
        val server = com.sporez.actualist.core.server.ServerClients.simpleFin(url, customHeaders(url))
        val token = token()
        // Actualist's provider choice: the server's SimpleFIN when configured, else this device's bridge key.
        val transport = com.sporez.actualist.core.banksync.bridge.SimpleFinBridge.choose(server, token, secrets.getString(SIMPLEFIN_BRIDGE))
        return com.sporez.actualist.core.banksync.SimpleFinSync(database, transport, token, clock)
    }

    /** Whether this device holds its own SimpleFIN access key (the direct bridge). */
    val hasSimpleFinBridge: Boolean get() = secrets.getString(SIMPLEFIN_BRIDGE) != null

    /** Claims a SimpleFIN setup token on this device; the access key stays in the Keystore-backed store. */
    suspend fun connectSimpleFinBridge(setupToken: String) = withContext(Dispatchers.IO) {
        secrets.putString(SIMPLEFIN_BRIDGE, com.sporez.actualist.core.banksync.bridge.SimpleFinBridge.claimToStoredKey(setupToken))
    }

    fun disconnectSimpleFinBridge() {
        secrets.remove(SIMPLEFIN_BRIDGE)
    }

    /** One sync pass for an open (non-demo) session. Never reports success early. */
    suspend fun sync(session: BudgetSession): SyncStatus {
        if (session.isDemo) return SyncStatus.LocalOnly.also(session::publishSyncStatus)
        val metadata = library.metadata(session.budgetId) ?: return fail(session, "This budget has no server link.")
        session.publishSyncStatus(SyncStatus.Syncing)
        return try {
            val result = session.syncing {
                val keyData = metadata.encryptKeyId?.let { secrets.get(SecretStore.budgetKey(session.budgetId)) ?: throw LocalFirstException.EncryptedBudgetRequiresPassword() }
                val engine = SyncEngine(
                    database,
                    client(),
                    SyncConfiguration(
                        fileId = metadata.cloudFileId ?: session.budgetId,
                        groupId = metadata.groupId,
                        encryptionKeyId = metadata.encryptKeyId,
                        encryption = keyData?.let { EncryptionContext(metadata.encryptKeyId!!, it) },
                    ),
                    clock,
                )
                var pass = engine.sync(token())
                var uploaded = pass.uploaded
                var applied = pass.applied
                // Drain later outbox batches in the same user-visible sync.
                var guard = 0
                while (pass.pendingAfter > 0 && guard++ < 50) {
                    pass = engine.sync(token())
                    uploaded += pass.uploaded
                    applied += pass.applied
                }
                Triple(uploaded, applied, pass.pendingAfter)
            }
            val now = System.currentTimeMillis()
            library.saveMetadata(metadata.copy(lastSyncedAtMillis = now))
            val status = if (result.third == 0) SyncStatus.Synced(now, result.second, result.first) else SyncStatus.Idle(result.third, now)
            status.also(session::publishSyncStatus)
        } catch (error: Exception) {
            fail(session, error.message ?: "Sync failed.")
        }
    }

    private fun fail(session: BudgetSession, message: String): SyncStatus =
        SyncStatus.Failed(message, session.database.pendingLocalMessageCount()).also(session::publishSyncStatus)

    private companion object {
        const val OPENID_PENDING = "openid.pending"
        const val CUSTOM_HEADERS = "server.custom_headers"
        const val SIMPLEFIN_BRIDGE = "simplefin.bridge_access_key"
    }
}
