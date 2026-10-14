package com.sporez.actualist.android

import com.sporez.actualist.core.sync.MerkleTrie
import com.sporez.actualist.core.sync.SyncProto
import com.sporez.actualist.core.sync.SyncTimestamp
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.TreeMap
import kotlin.concurrent.thread

/**
 * A tiny Actual-compatible server on 127.0.0.1 inside the test process: password login,
 * file list and download, and `/sync/sync` with the real server's semantics (read
 * `timestamp > since` before inserting, first envelope per timestamp wins, merkle of all
 * stored timestamps). Synthetic data only; nothing leaves the emulator.
 */
class LoopbackActualServer(
    private val fileId: String = "loopback-file",
    private val groupId: String = "loopback-group",
    private val budgetName: String = "Loopback Synthetic Budget",
) : Closeable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val lock = Any()
    private val messages = TreeMap<String, SyncProto.MessageEnvelope>()
    /** Files registered through upload-user-file, by id; each shares this server's message log. */
    val uploadedNames = java.util.concurrent.ConcurrentHashMap<String, String>()
    val simpleFinRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
    /** The return address the app sent when it started OpenID sign-in. */
    @Volatile
    var openIdReturnUrl: String? = null
    private var trie = MerkleTrie.EMPTY
    @Volatile var budgetZip: ByteArray = ByteArray(0)
    /** The `/account/login-methods` answer; a test can make this a header-only server. */
    @Volatile var loginMethodsJson: String =
        """{"status":"ok","methods":[{"method":"password","active":1,"displayName":"Password"},{"method":"openid","active":1,"displayName":"OpenID"}]}"""
    @Volatile private var running = true
    val requests = java.util.concurrent.CopyOnWriteArrayList<String>()

    val url: String get() = "http://127.0.0.1:${socket.localPort}"

    val storedValues: List<String>
        get() = synchronized(lock) {
            messages.values.map { SyncProto.decodeMessage(it.content).let { m -> "${m.dataset}/${m.column}=${m.value}" } }
        }

    val storedCount: Int get() = synchronized(lock) { messages.size }

    init {
        thread(isDaemon = true, name = "loopback-actual") {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                thread(isDaemon = true) { client.use { handle(it) } }
            }
        }
    }

    /** The sync handler, also used directly to seed the server before the app downloads. */
    fun sync(body: ByteArray): ByteArray = synchronized(lock) {
        val request = SyncProto.decodeRequest(body)
        require((request.fileId == fileId || uploadedNames.containsKey(request.fileId)) && request.groupId == groupId) { "wrong file or group" }
        val reply = messages.tailMap(request.since, false).values.toList()
        for (envelope in request.messages) {
            if (envelope.timestamp !in messages) {
                messages[envelope.timestamp] = envelope
                trie = trie.insert(SyncTimestamp.parse(envelope.timestamp)!!)
            }
        }
        SyncProto.encode(SyncProto.SyncResponse(reply, trie.pruned().jsonString))
    }

    private fun handle(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val (method, path) = requestLine.split(" ").let { it[0] to it[1].substringBefore('?') }
        requests += "$method $path"
        val token = headers["x-actual-token"]
        val (status, type, payload) = when {
            path == "/account/login-methods" -> Triple(
                200,
                "application/json",
                loginMethodsJson.toByteArray(),
            )
            path == "/account/login" && String(body).contains("\"loginMethod\":\"openid\"") -> {
                openIdReturnUrl = Regex("\"returnUrl\":\"([^\"]+)\"").find(String(body))?.groupValues?.get(1)
                Triple(200, "application/json", """{"status":"ok","data":{"returnUrl":"$url/fake-idp"}}""".toByteArray())
            }
            path == "/fake-idp" -> Triple(200, "text/html", "<html><body>Synthetic identity provider</body></html>".toByteArray())
            path == "/account/login" -> if (String(body).contains("\"password\":\"$PASSWORD\"")) {
                Triple(200, "application/json", """{"status":"ok","data":{"token":"$TOKEN"}}""".toByteArray())
            } else {
                Triple(400, "application/json", """{"status":"error","reason":"invalid-password"}""".toByteArray())
            }
            token != TOKEN -> Triple(401, "application/json", """{"status":"error","reason":"unauthorized"}""".toByteArray())
            path == "/sync/list-user-files" -> Triple(
                200,
                "application/json",
                """{"status":"ok","data":[{"deleted":0,"fileId":"$fileId","groupId":"$groupId","name":"$budgetName","encryptKeyId":null}]}""".toByteArray(),
            )
            path == "/sync/download-user-file" -> Triple(200, "application/octet-stream", budgetZip)
            path == "/sync/upload-user-file" -> {
                val id = headers["x-actual-file-id"] ?: ""
                uploadedNames[id] = java.net.URLDecoder.decode(headers["x-actual-name"] ?: "", "UTF-8")
                budgetZip = body
                Triple(200, "application/json", """{"status":"ok","groupId":"$groupId"}""".toByteArray())
            }
            path == "/sync/sync" -> Triple(200, "application/actual-sync", sync(body))
            // SimpleFIN through the server, as Actualist uses it; synthetic accounts and rows only.
            path == "/simplefin/status" -> Triple(200, "application/json", """{"status":"ok","data":{"configured":true}}""".toByteArray())
            path == "/simplefin/accounts" -> Triple(
                200,
                "application/json",
                """{"status":"ok","data":{"accounts":[{"id":"remote-1","name":"Synthetic Bank Checking","balance":"100.00","currency":"USD","org":{"name":"Synthetic Bank","domain":"bank.example","id":"org-1"}}]}}""".toByteArray(),
            )
            path == "/simplefin/transactions" -> {
                simpleFinRequests += String(body)
                val day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(2).toString()
                Triple(
                    200,
                    "application/json",
                    ("""{"status":"ok","data":{"remote-1":{"transactions":{"all":[""" +
                        """{"transactionId":"sf-1","date":"$day","transactionAmount":{"amount":"-15.00","currency":"USD"},"payeeName":"Synthetic Bank Market","booked":true}""" +
                        """]},"balances":[{"balanceAmount":{"amount":"100.00","currency":"USD"}}],"startingBalance":10000}}}""").toByteArray(),
                )
            }
            else -> Triple(404, "text/plain", "not found".toByteArray())
        }
        val out = client.getOutputStream()
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(payload)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) return bytes.toString(Charsets.UTF_8.name()).trimEnd('\r')
            bytes.write(b)
        }
    }

    override fun close() {
        running = false
        socket.close()
    }

    companion object {
        const val PASSWORD = "loopback-password"
        const val TOKEN = "loopback-token"
    }
}
