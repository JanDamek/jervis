@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.jervis.ui.voice

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import platform.Foundation.*
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * iOS WebSocket client using native NSURLSessionWebSocketTask.
 *
 * NSURLSessionWebSocketTask works on iOS and watchOS when AVAudioSession is active.
 * Supports both text (JSON) and binary (PCM audio) frames natively.
 */
actual class VoiceWebSocketClient actual constructor() {
    private var task: NSURLSessionWebSocketTask? = null
    private var session: NSURLSession? = null
    private val incoming = Channel<Pair<Boolean, ByteArray>>(Channel.BUFFERED)
    private var _isConnected = false
    private var receiveActive = false

    actual val isConnected: Boolean get() = _isConnected

    actual suspend fun connect(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: throw IllegalArgumentException("Invalid URL: $url")
        val config = NSURLSessionConfiguration.defaultSessionConfiguration
        config.timeoutIntervalForRequest = 300.0 // 5 min
        session = NSURLSession.sessionWithConfiguration(config)
        task = session?.webSocketTaskWithURL(nsUrl)
        task?.resume()
        _isConnected = true
        scheduleReceive()
    }

    private fun scheduleReceive() {
        if (!_isConnected || receiveActive) return
        receiveActive = true
        task?.receiveMessageWithCompletionHandler { message, error ->
            receiveActive = false
            if (error != null) {
                _isConnected = false
                incoming.close()
                return@receiveMessageWithCompletionHandler
            }
            if (message != null) {
                when (message.type) {
                    NSURLSessionWebSocketMessageTypeString -> {
                        val text = message.string ?: ""
                        incoming.trySend(true to text.encodeToByteArray())
                    }
                    NSURLSessionWebSocketMessageTypeData -> {
                        val nsData = message.data ?: return@receiveMessageWithCompletionHandler
                        val bytes = ByteArray(nsData.length.toInt())
                        bytes.usePinned { pinned ->
                            memcpy(pinned.addressOf(0), nsData.bytes, nsData.length)
                        }
                        incoming.trySend(false to bytes)
                    }
                    else -> {}
                }
                // Schedule next receive
                if (_isConnected) scheduleReceive()
            }
        }
    }

    actual suspend fun sendAudio(pcmChunk: ByteArray) {
        if (!_isConnected) return
        val nsData = pcmChunk.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), pcmChunk.size.toULong())
        }
        val message = NSURLSessionWebSocketMessage(nsData)
        task?.sendMessage(message) { error ->
            if (error != null) {
                println("VoiceWS iOS: send audio error: ${error.localizedDescription}")
            }
        }
    }

    actual suspend fun sendText(json: String) {
        if (!_isConnected) return
        val message = NSURLSessionWebSocketMessage(json)
        task?.sendMessage(message) { error ->
            if (error != null) {
                println("VoiceWS iOS: send text error: ${error.localizedDescription}")
            }
        }
    }

    actual suspend fun receive(): Pair<Boolean, ByteArray>? {
        return try {
            incoming.receive()
        } catch (_: Exception) {
            null
        }
    }

    actual suspend fun close() {
        _isConnected = false
        task?.cancelWithCloseCode(NSURLSessionWebSocketCloseCodeNormalClosure, null)
        task = null
        session?.invalidateAndCancel()
        session = null
        incoming.close()
    }
}
