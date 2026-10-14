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

    private fun client(url: String = settings.settings.value.serverUrl ?: throw ServerException.InvalidUrl()) = ActualServerClient(url)

    private fun token(): String = secrets.getString(SecretStore.SERVER_TOKEN) ?: throw ServerException.AuthenticationFailed()

    suspend fun signIn(serverUrl: String, password: String) = withContext(Dispatchers.IO) {
        val client = ActualServerClient(serverUrl)
        val methods = client.loginMethods()
        if ("password" !in methods) throw ServerException.HttpStatus(501)
        val token = client.login(password)
        secrets.putString(SecretStore.SERVER_TOKEN, token)
        settings.update { it.copy(serverUrl = client.baseUrl.toString()) }
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
}
