package com.jervis.ui.notification

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.io.path.exists

object MacAppSocketBridge {
    data class TokenMessage(val hexToken: String, val deviceId: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenDeferred = CompletableDeferred<TokenMessage>()
    private var started = false
    private var deviceId: String? = null

    @Volatile
    private var loginItemEnabled: Boolean? = null
    private val loginItemListeners = mutableListOf<(Boolean) -> Unit>()

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var channel: SocketChannel? = null
    private val writeMutex = Mutex()
    private val pendingWrites = mutableListOf<String>()

    fun ensureStarted(socketPath: String): MacAppSocketBridge {
        if (started) return this
        started = true
        scope.launch { runLoop(socketPath) }
        return this
    }

    suspend fun awaitToken(): TokenMessage = tokenDeferred.await()

    fun currentDeviceId(): String? = deviceId

    fun showNotification(
        taskId: String?,
        title: String,
        body: String,
        category: String? = null,
        payload: Map<String, String> = emptyMap(),
    ) {
        val obj = buildJsonObject {
            put("kind", JsonPrimitive("showNotification"))
            taskId?.let { put("taskId", JsonPrimitive(it)) }
            put("title", JsonPrimitive(title))
            put("body", JsonPrimitive(body))
            category?.let { put("category", JsonPrimitive(it)) }
            if (payload.isNotEmpty()) {
                put(
                    "payload",
                    buildJsonObject { payload.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
                )
            }
        }
        scope.launch { writeLine(obj.toString()) }
    }

    fun cancelNotification(taskId: String) {
        val obj = buildJsonObject {
            put("kind", JsonPrimitive("cancelNotification"))
            put("taskId", JsonPrimitive(taskId))
        }
        scope.launch { writeLine(obj.toString()) }
    }

    fun setLoginItem(enabled: Boolean) {
        val obj = buildJsonObject {
            put("kind", JsonPrimitive("setLoginItem"))
            put("enabled", JsonPrimitive(enabled))
        }
        scope.launch { writeLine(obj.toString()) }
    }

    fun queryLoginItem() {
        val obj = buildJsonObject { put("kind", JsonPrimitive("queryLoginItem")) }
        scope.launch { writeLine(obj.toString()) }
    }

    fun focusJervis() {
        val obj = buildJsonObject { put("kind", JsonPrimitive("focusJervis")) }
        scope.launch { writeLine(obj.toString()) }
    }

    fun loginItemEnabled(): Boolean? = loginItemEnabled

    fun addLoginItemListener(listener: (Boolean) -> Unit) {
        synchronized(loginItemListeners) { loginItemListeners.add(listener) }
        loginItemEnabled?.let { listener(it) }
    }

    private suspend fun writeLine(line: String) {
        writeMutex.withLock {
            val ch = channel
            val full = line + "\n"
            if (ch != null && ch.isConnected) {
                runCatching {
                    ch.write(ByteBuffer.wrap(full.toByteArray(Charsets.UTF_8)))
                }.onFailure {
                    pendingWrites.add(full)
                }
            } else {
                pendingWrites.add(full)
            }
        }
    }

    private suspend fun flushPending() {
        writeMutex.withLock {
            val ch = channel ?: return
            val drained = pendingWrites.toList()
            pendingWrites.clear()
            for (line in drained) {
                runCatching {
                    ch.write(ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8)))
                }.onFailure {
                    pendingWrites.add(line)
                    return
                }
            }
        }
    }

    private suspend fun runLoop(socketPath: String) {
        val path = Path.of(socketPath)
        var lastErrorLogged: String? = null
        while (scope.isActive) {
            if (!path.exists()) {
                delay(2_000L)
                continue
            }
            val outcome = runCatching { openAndRead(path) }
            outcome.onFailure { e ->
                val msg = e.message ?: e::class.simpleName ?: "unknown"
                if (msg != lastErrorLogged) {
                    println("macApp socket error: $msg")
                    lastErrorLogged = msg
                }
            }
            if (outcome.isSuccess) lastErrorLogged = null
            channel = null
            delay(2_000L)
        }
    }

    private suspend fun openAndRead(path: Path) {
        val address = UnixDomainSocketAddress.of(path)
        SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
            ch.connect(address)
            channel = ch
            flushPending()
            val buffer = StringBuilder()
            val bytes = ByteBuffer.allocate(4096)
            while (true) {
                bytes.clear()
                val read = ch.read(bytes)
                if (read < 0) return
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

    private suspend fun handleLine(line: String) {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        when (obj["kind"]?.jsonPrimitive?.content) {
            "token" -> handleToken(obj)
            "payload" -> handlePayload(obj)
            "action" -> handleAction(obj)
            "loginItemStatus" -> handleLoginItemStatus(obj)
            else -> println("macApp socket: unknown kind in $line")
        }
    }

    private fun handleLoginItemStatus(obj: JsonObject) {
        val enabled = obj["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: return
        loginItemEnabled = enabled
        val snapshot = synchronized(loginItemListeners) { loginItemListeners.toList() }
        snapshot.forEach { it(enabled) }
    }

    private fun handleToken(obj: JsonObject) {
        val hex = obj["hexToken"]?.jsonPrimitive?.content ?: return
        val id = obj["deviceId"]?.jsonPrimitive?.content ?: return
        deviceId = id
        if (!tokenDeferred.isCompleted) tokenDeferred.complete(TokenMessage(hex, id))
    }

    private fun handlePayload(obj: JsonObject) {
        println("macApp push payload: ${obj["userInfo"]}")
    }

    private suspend fun handleAction(obj: JsonObject) {
        val taskId = obj["taskId"]?.jsonPrimitive?.content ?: ""
        val actionStr = obj["action"]?.jsonPrimitive?.content ?: return
        val replyText = obj["replyText"]?.jsonPrimitive?.content
        val notifAction = when (actionStr) {
            "APPROVE" -> NotificationAction.APPROVE
            "DENY" -> NotificationAction.DENY
            "REPLY" -> NotificationAction.REPLY
            "OPEN" -> NotificationAction.OPEN
            else -> return
        }
        NotificationActionChannel.actions.emit(
            NotificationActionResult(
                taskId = taskId,
                action = notifAction,
                replyText = replyText,
            ),
        )
    }
}
