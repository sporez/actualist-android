package com.sporez.actualist.core.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Failures talking to an Actual server, with user-facing messages that never contain secrets. */
sealed class ServerException(message: String) : Exception(message) {
    class InvalidUrl : ServerException("The server URL is invalid.")
    class InsecureUrl : ServerException(ServerConnectionSecurity.REMOTE_HTTP_BLOCKED)
    class AuthenticationFailed : ServerException("Your Actual session is no longer valid. Sign in again to resume syncing.")
    class WrongPassword : ServerException("The server password is incorrect.")
    class RedirectRefused : ServerException("The server redirected the request. Enter the final server URL directly.")
    class SyncRejected(val reason: String) : ServerException(
        when (reason) {
            "file-has-new-key", "file-key-mismatch" -> "This budget's encryption no longer matches the server. Download it again."
            "file-has-reset" -> "The server reset this budget's sync state. Download it again to continue syncing."
            "file-old-version" -> "This budget uses an older sync format the server no longer accepts."
            "file-needs-upload" -> "The server needs this budget uploaded again before it can sync."
            else -> "The server refused to sync this budget ($reason)."
        },
    )
    class HttpStatus(val status: Int) : ServerException("The server returned HTTP $status.")
    class ResponseTooLarge : ServerException("The server response was larger than this app accepts.")
    class InvalidResponse : ServerException("The server returned a response this app could not read.")
    class Unreachable(cause: IOException) : ServerException("Could not reach the server. Check the URL and your connection.") {
        init {
            initCause(cause)
        }
    }

    val isAuthenticationFailure: Boolean get() = this is AuthenticationFailed
}

data class RemoteFile(
    val fileId: String,
    val groupId: String?,
    val name: String,
    val encryptKeyId: String?,
    /** Present when the uploaded file itself is encrypted. */
    val encryptMeta: EncryptMeta?,
)

data class EncryptMeta(val keyId: String, val algorithm: String?, val iv: String?, val authTag: String?)

data class UserKey(val id: String, val salt: String, val test: String?)

/** The sync transport seam: one `/sync/sync` round trip. Tests substitute a fake server. */
fun interface SyncTransport {
    fun sync(body: ByteArray, token: String): ByteArray
}

/**
 * Client for a normal Actual sync server (not the REST wrapper). Endpoints and headers
 * follow Actualist's `ActualServerSyncClient`: `X-ACTUAL-TOKEN`, `X-ACTUAL-FILE-ID`,
 * protobuf bodies as `application/actual-sync`. Redirects are refused, and plain http is
 * accepted only for local-network hosts ([ServerConnectionSecurity]).
 */
class ActualServerClient(
    serverUrl: String,
    private val http: OkHttpClient = defaultHttpClient(),
    private val maximumSyncResponseBytes: Long = 64L * 1024 * 1024,
    private val maximumDownloadBytes: Long = 256L * 1024 * 1024,
) : SyncTransport {
    val baseUrl: HttpUrl = validatedUrl(serverUrl)

    /** The normalized server URL, for storing in settings. */
    val url: String get() = baseUrl.toString()
    private val json = Json { ignoreUnknownKeys = true }

    /** Login methods the server offers (`password`, `openid`, `header`). */
    fun loginMethods(): List<String> {
        val body = execute(request("account/login-methods").get().build()) { it.bodyText() }
        val root = parse(body)
        val methods = (root["methods"] ?: (root["data"] as? JsonObject)?.get("methods")) as? JsonArray ?: return listOf("password")
        return methods.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val active = (obj["active"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.intOrNull?.let { n -> n != 0 }) } ?: true
            if (active) (obj["method"] as? JsonPrimitive)?.contentOrNull else null
        }
    }

    /**
     * Starts an OpenID login (Actualist `beginOpenIDLogin`): the server answers with the identity
     * provider URL to open in a browser, which finally redirects to [returnUrl]`/openid-cb?token=`.
     */
    fun beginOpenIdLogin(returnUrl: String, firstTimeLoginPassword: String? = null): String {
        val payload = buildJsonObject {
            put("loginMethod", "openid")
            put("returnUrl", returnUrl)
            firstTimeLoginPassword?.let { put("password", it) }
        }.toString()
        val text = execute(request("account/login").post(payload.toRequestBody(JSON)).build()) { it.bodyText() }
        val root = parse(text)
        val url = (root["returnUrl"] as? JsonPrimitive)?.contentOrNull
            ?: ((root["data"] as? JsonObject)?.get("returnUrl") as? JsonPrimitive)?.contentOrNull
            ?: throw ServerException.InvalidResponse()
        val parsed = url.toHttpUrlOrNull() ?: throw OpenIdException("The server returned an invalid sign-in address.")
        if (parsed.scheme != "http" && parsed.scheme != "https") throw OpenIdException("The server returned an invalid sign-in address.")
        return url
    }

    /** Password login; returns the session token. */
    fun login(password: String): String {
        val payload = buildJsonObject {
            put("loginMethod", "password")
            put("password", password)
        }.toString()
        val response = http.newCall(request("account/login").post(payload.toRequestBody(JSON)).build()).executeSafely()
        response.use {
            val text = it.bodyText()
            if (it.code == 400 || it.code == 401 || it.code == 403) throw ServerException.WrongPassword()
            if (!it.isSuccessful) throw errorFor(it.code, text)
            val root = parse(text)
            val token = ((root["data"] as? JsonObject)?.get("token") as? JsonPrimitive)?.contentOrNull
                ?: (root["token"] as? JsonPrimitive)?.contentOrNull
            if (token.isNullOrEmpty()) {
                if ((root["status"] as? JsonPrimitive)?.contentOrNull == "error") throw ServerException.WrongPassword()
                throw ServerException.InvalidResponse()
            }
            return token
        }
    }

    fun listUserFiles(token: String): List<RemoteFile> {
        val text = execute(request("sync/list-user-files").header(TOKEN, token).get().build()) { it.bodyText() }
        val root = parse(text)
        val data = root["data"]
        val (files, topGroup) = when (data) {
            is JsonArray -> data to null
            is JsonObject -> ((data["files"] as? JsonArray) ?: JsonArray(emptyList())) to (data["groupId"] as? JsonPrimitive)?.contentOrNull
            else -> throw ServerException.InvalidResponse()
        }
        val seen = HashSet<String>()
        return files.mapNotNull { element -> remoteFile(element as? JsonObject ?: return@mapNotNull null, topGroup) }
            .filter { seen.add(it.fileId) }
    }

    fun userFileInfo(token: String, fileId: String): RemoteFile? {
        val text = execute(request("sync/get-user-file-info").header(TOKEN, token).header(FILE_ID, fileId).get().build()) { it.bodyText() }
        val data = parse(text)["data"] as? JsonObject ?: return null
        return remoteFile(data, null)
    }

    private fun remoteFile(obj: JsonObject, topGroup: String?): RemoteFile? {
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotEmpty() }
        val deleted = (obj["deleted"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.intOrNull?.let { n -> n != 0 }) } ?: false
        if (deleted) return null
        val fileId = str("fileId") ?: str("cloudFileId") ?: str("id") ?: return null
        val meta = (obj["encryptMeta"] as? JsonObject)?.let { meta ->
            fun m(key: String) = (meta[key] as? JsonPrimitive)?.contentOrNull
            m("keyId")?.let { EncryptMeta(it, m("algorithm"), m("iv"), m("authTag")) }
        }
        return RemoteFile(
            fileId = fileId,
            groupId = str("groupId") ?: topGroup,
            name = str("name") ?: fileId,
            encryptKeyId = str("encryptKeyId") ?: str("encryptionKeyId") ?: meta?.keyId,
            encryptMeta = meta,
        )
    }

    fun userKey(token: String, fileId: String): UserKey {
        val payload = buildJsonObject { put("fileId", fileId) }.toString()
        val text = execute(request("sync/user-get-key").header(TOKEN, token).header(FILE_ID, fileId).post(payload.toRequestBody(JSON)).build()) { it.bodyText() }
        val root = parse(text)
        val data = (root["data"] as? JsonObject) ?: root
        fun str(key: String) = (data[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        val id = str("id") ?: str("keyId") ?: throw ServerException.InvalidResponse()
        val salt = str("salt") ?: throw ServerException.InvalidResponse()
        return UserKey(id, salt, str("test"))
    }

    /** The budget file as uploaded: a zip, or AES-GCM ciphertext of the zip when `encryptMeta` is set. */
    fun downloadUserFile(token: String, fileId: String): ByteArray = execute(
        request("sync/download-user-file").header(TOKEN, token).header(FILE_ID, fileId).header("Accept", "application/octet-stream").get().build(),
    ) { it.bodyBytes(maximumDownloadBytes) }

    /**
     * Registers a budget archive as a new (or replacement) server file, as Actualist's
     * `uploadUserFile` does. Returns the group id the server assigned, when it sends one.
     */
    fun uploadUserFile(token: String, fileId: String, name: String, groupId: String?, archive: ByteArray, encryptMeta: EncryptMeta? = null): String? {
        val builder = request("sync/upload-user-file")
            .header(TOKEN, token)
            .header(FILE_ID, fileId)
            .header("X-ACTUAL-NAME", encodeUriComponent(name))
            .header("X-ACTUAL-FORMAT", "2")
            .post(archive.toRequestBody("application/encrypted-file".toMediaType()))
        groupId?.let { builder.header("X-ACTUAL-GROUP-ID", it) }
        encryptMeta?.let { meta ->
            builder.header(
                "X-ACTUAL-ENCRYPT-META",
                buildJsonObject {
                    put("keyId", meta.keyId)
                    meta.algorithm?.let { put("algorithm", it) }
                    meta.iv?.let { put("iv", it) }
                    meta.authTag?.let { put("authTag", it) }
                }.toString(),
            )
        }
        val text = execute(builder.build()) { it.bodyText() }
        val root = runCatching { parse(text) }.getOrNull() ?: return null
        if ((root["status"] as? JsonPrimitive)?.contentOrNull == "error") throw ServerException.InvalidResponse()
        return (root["groupId"] as? JsonPrimitive)?.contentOrNull ?: ((root["data"] as? JsonObject)?.get("groupId") as? JsonPrimitive)?.contentOrNull
    }

    /** Registers a budget's encryption key (`user-create-key`); only `status: ok` confirms it. */
    fun createUserKey(token: String, fileId: String, keyId: String, keySalt: String, testContent: String) {
        val payload = buildJsonObject {
            put("fileId", fileId)
            put("keyId", keyId)
            put("keySalt", keySalt)
            put("testContent", testContent)
        }.toString()
        val text = execute(request("sync/user-create-key").header(TOKEN, token).post(payload.toRequestBody(JSON)).build()) { it.bodyText() }
        val status = runCatching { (parse(text)["status"] as? JsonPrimitive)?.contentOrNull }.getOrNull()
        if (status?.lowercase() != "ok") throw ServerException.InvalidResponse()
    }

    /** Removes a server file (`delete-user-file`); used to withdraw a half-registered budget. */
    fun deleteUserFile(token: String, fileId: String) {
        val payload = buildJsonObject { put("fileId", fileId) }.toString()
        execute(request("sync/delete-user-file").header(TOKEN, token).post(payload.toRequestBody(JSON)).build()) { it.bodyText() }
    }

    override fun sync(body: ByteArray, token: String): ByteArray = execute(
        request("sync/sync").header(TOKEN, token).post(body.toRequestBody(SYNC)).build(),
    ) { it.bodyBytes(maximumSyncResponseBytes) }

    // region Plumbing

    private fun request(path: String): Request.Builder {
        val url = baseUrl.newBuilder().addPathSegments(path).build()
        return Request.Builder().url(url)
    }

    private fun <T> execute(request: Request, read: (Response) -> T): T {
        val response = http.newCall(request).executeSafely()
        response.use {
            if (it.isRedirect) throw ServerException.RedirectRefused()
            if (!it.isSuccessful) throw errorFor(it.code, it.bodyText())
            return read(it)
        }
    }

    private fun okhttp3.Call.executeSafely(): Response = try {
        execute()
    } catch (error: IOException) {
        throw ServerException.Unreachable(error)
    }

    private fun errorFor(status: Int, body: String): ServerException {
        if (status == 401 || status == 403) return ServerException.AuthenticationFailed()
        val token = body.trim()
        if (token in SYNC_REJECTIONS) return ServerException.SyncRejected(token)
        val reason = runCatching { (parse(body)["reason"] as? JsonPrimitive)?.contentOrNull }.getOrNull()
        if (reason == "unauthorized" || reason == "token-expired") return ServerException.AuthenticationFailed()
        return ServerException.HttpStatus(status)
    }

    private fun parse(text: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject ?: throw ServerException.InvalidResponse()
    } catch (error: ServerException) {
        throw error
    } catch (error: Exception) {
        throw ServerException.InvalidResponse()
    }

    private fun Response.bodyText(): String = body.string()

    private fun Response.bodyBytes(limit: Long): ByteArray {
        val declared = body.contentLength()
        if (declared > limit) throw ServerException.ResponseTooLarge()
        val source = body.source()
        // `request` buffers until it has the bytes or the body ends: true means over the limit.
        if (source.request(limit + 1)) throw ServerException.ResponseTooLarge()
        return source.readByteArray()
    }

    // endregion

    companion object {
        const val TOKEN = "X-ACTUAL-TOKEN"
        const val FILE_ID = "X-ACTUAL-FILE-ID"
        private val JSON = "application/json".toMediaType()
        private val SYNC = "application/actual-sync".toMediaType()
        private val SYNC_REJECTIONS = setOf("file-old-version", "file-needs-upload", "file-key-mismatch", "file-has-reset", "file-has-new-key")

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /** JavaScript `encodeURIComponent`, which Actual's server reverses for `X-ACTUAL-NAME`. */
        fun encodeUriComponent(text: String): String {
            val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
            val out = StringBuilder()
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                val char = (byte.toInt() and 0xFF).toChar()
                if (byte >= 0 && char in unreserved) out.append(char) else out.append(String.format(java.util.Locale.ROOT, "%%%02X", byte.toInt() and 0xFF))
            }
            return out.toString()
        }

        /** https anywhere; http only for local-network hosts (Actualist's rule). */
        fun validatedUrl(serverUrl: String): HttpUrl {
            val trimmed = serverUrl.trim().trimEnd('/')
            val url = trimmed.toHttpUrlOrNull() ?: throw ServerException.InvalidUrl()
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw ServerException.InvalidUrl()
            if (!url.isHttps && !ServerConnectionSecurity.isLocalNetworkHost(url.host)) throw ServerException.InsecureUrl()
            return url
        }
    }
}
