package com.jervis.ui.notification

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Reads the JSON-per-line stream produced by the `apps/macApp`
 * Swift host. One instance per JVM process, started lazily when
 * the `JERVIS_MACAPP_SOCKET` env variable points at a live socket.
 *
 * Messages:
 *   { "kind": "token",   "hexToken": "...", "deviceId": "..." }
 *   { "kind": "payload", "userInfo": { ... } }
 *
 * `awaitToken()` suspends until the first token arrives (the Swift
 * host sends it right after `didRegisterForRemoteNotifications...`).
 * Subsequent payloads are logged — hooking them into the Compose
 * notification UI is tracked in project-macos-native-push-wrapper.
 */
internal object MacAppSocketBridge {
    data class TokenMessage(val hexToken: String, val deviceId: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenDeferred = CompletableDeferred<TokenMessage>()
    private var started = false
    private var deviceId: String? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun ensureStarted(socketPath: String): MacAppSocketBridge {
        if (started) return this
        started = true
        scope.launch { runLoop(socketPath) }
        return this
    }

    suspend fun awaitToken(): TokenMessage = tokenDeferred.await()

    fun currentDeviceId(): String? = deviceId

    private suspend fun runLoop(socketPath: String) {
        val path = Path.of(socketPath)
        while (scope.isActive) {
            if (!path.exists()) {
                delay(500L)
                continue
            }
            runCatching { openAndRead(path) }
                .onFailure { e -> println("macApp socket error: ${e.message}") }
            delay(1_000L) // backoff before reconnecting if Swift host restarts
        }
    }

    private fun openAndRead(path: Path) {
        val address = UnixDomainSocketAddress.of(path)
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(address)
            val buffer = StringBuilder()
            val bytes = ByteBuffer.allocate(4096)
            while (true) {
                bytes.clear()
                val read = channel.read(bytes)
                if (read < 0) return // Swift host closed the connection
                bytes.flip()
                val chunk = Charsets.UTF_8.decode(bytes).toString()
                buffer.append(chunk)
                while (true) {
                    val nl = buffer.indexOf('\n')
                    if (nl < 0) break
                    val line = buffer.substring(0, nl).trim()
                    buffer.delete(0, nl + 1)
                    if (line.isNotEmpty()) handleLine(line)
                }
            }
        }
    }

    private fun handleLine(line: String) {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        when (obj["kind"]?.jsonPrimitive?.content) {
            "token" -> handleToken(obj)
            "payload" -> handlePayload(obj)
            else -> println("macApp socket: unknown kind in $line")
        }
    }

    private fun handleToken(obj: JsonObject) {
        val hex = obj["hexToken"]?.jsonPrimitive?.content ?: return
        val id = obj["deviceId"]?.jsonPrimitive?.content ?: return
        deviceId = id
        if (!tokenDeferred.isCompleted) tokenDeferred.complete(TokenMessage(hex, id))
    }

    private fun handlePayload(obj: JsonObject) {
        // Payload forwarding into the Compose notification UI is a
        // follow-up — for now we log so operators can confirm the
        // socket pipe is working end-to-end.
        println("macApp push payload: ${obj["userInfo"]}")
    }
}
