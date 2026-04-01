package com.jervis.ui.voice

/**
 * Platform-specific WebSocket client for continuous voice sessions.
 *
 * Binary frames: PCM 16kHz mono 16-bit audio chunks
 * Text frames: JSON control/event messages
 *
 * Implementations:
 * - JVM/Android: Ktor WebSocket client
 * - iOS: NSURLSessionWebSocketTask (native, supports active AVAudioSession)
 */
expect class VoiceWebSocketClient() {
    /** Connect to WebSocket server. */
    suspend fun connect(url: String)

    /** Send binary PCM audio chunk. */
    suspend fun sendAudio(pcmChunk: ByteArray)

    /** Send JSON control message. */
    suspend fun sendText(json: String)

    /** Receive next frame. Returns Pair(isText, data). Null on close. */
    suspend fun receive(): Pair<Boolean, ByteArray>?

    /** Close connection gracefully. */
    suspend fun close()

    /** Whether the connection is open. */
    val isConnected: Boolean
}
