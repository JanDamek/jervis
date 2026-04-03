package com.jervis.ui.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android WebSocket client using Ktor OkHttp engine.
 */
actual class VoiceWebSocketClient actual constructor() {
    private val httpClient = HttpClient(OkHttp) {
        install(WebSockets) {
            pingIntervalMillis = 20_000
        }
    }

    private var outgoingAudio = Channel<ByteArray>(Channel.BUFFERED)
    private var outgoingText = Channel<String>(Channel.BUFFERED)
    private var incoming = Channel<Pair<Boolean, ByteArray>>(Channel.BUFFERED)
    private var sessionJob: Job? = null
    @Volatile
    private var _isConnected = false

    actual val isConnected: Boolean get() = _isConnected

    actual suspend fun connect(url: String) {
        // Reset channels for fresh connection
        outgoingAudio = Channel(Channel.BUFFERED)
        outgoingText = Channel(Channel.BUFFERED)
        incoming = Channel(Channel.BUFFERED)

        val connectedSignal = CompletableDeferred<Boolean>()

        sessionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                httpClient.webSocket(url) {
                    _isConnected = true
                    connectedSignal.complete(true)

                    val audioSender = launch {
                        for (chunk in outgoingAudio) { send(Frame.Binary(true, chunk)) }
                    }
                    val textSender = launch {
                        for (text in outgoingText) { send(Frame.Text(text)) }
                    }
                    try {
                        for (frame in this.incoming) {
                            when (frame) {
                                is Frame.Text -> this@VoiceWebSocketClient.incoming.send(true to frame.readText().encodeToByteArray())
                                is Frame.Binary -> this@VoiceWebSocketClient.incoming.send(false to frame.readBytes())
                                else -> {}
                            }
                        }
                    } catch (_: ClosedReceiveChannelException) {}
                    audioSender.cancel()
                    textSender.cancel()
                    _isConnected = false
                }
            } catch (e: Exception) {
                println("VoiceWebSocketClient Android: connection error: ${e.message}")
                _isConnected = false
                connectedSignal.complete(false)
            }
        }

        // Wait for WebSocket to actually connect (max 10s)
        val connected = withTimeoutOrNull(10_000L) { connectedSignal.await() } ?: false
        if (!connected) {
            println("VoiceWebSocketClient Android: connection timed out or failed")
            _isConnected = false
        }
    }

    actual suspend fun sendAudio(pcmChunk: ByteArray) {
        if (_isConnected) outgoingAudio.send(pcmChunk)
    }

    actual suspend fun sendText(json: String) {
        if (_isConnected) outgoingText.send(json)
    }

    actual suspend fun receive(): Pair<Boolean, ByteArray>? {
        return try { incoming.receive() } catch (_: Exception) { null }
    }

    actual suspend fun close() {
        _isConnected = false
        outgoingAudio.close()
        outgoingText.close()
        incoming.close()
        sessionJob?.cancelAndJoin()
    }
}
