@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.jervis.di

import com.jervis.api.SecurityConstants
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.Channel
import platform.Foundation.*
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * iOS SSE implementation using native NSURLSession with data delegate.
 *
 * Darwin Ktor engine buffers the entire response before yielding it,
 * so bodyAsChannel().readUTF8Line() does NOT work for SSE streaming.
 * This implementation uses NSURLSessionDataDelegate to receive data
 * incrementally as the server sends each SSE chunk.
 */
actual suspend fun postSseStream(
    url: String,
    bodyBytes: ByteArray,
    contentType: String,
    onEvent: suspend (SseEvent) -> Unit,
) {
    val eventChannel = Channel<SseEvent>(Channel.UNLIMITED)

    val delegate = SseDataDelegate(eventChannel)

    val config = NSURLSessionConfiguration.defaultSessionConfiguration.apply {
        // Live assist / meeting companion streams must persist for the full
        // meeting duration. Cap only per-chunk inactivity via Request timeout
        // (resets on each received chunk); never cap the whole resource.
        timeoutIntervalForRequest = 120.0
        timeoutIntervalForResource = Double.MAX_VALUE
        waitsForConnectivity = false
    }
    val session = NSURLSession.sessionWithConfiguration(config, delegate, NSOperationQueue.mainQueue)

    val request = NSMutableURLRequest(NSURL(string = url)!!).apply {
        setHTTPMethod("POST")
        setValue(contentType, forHTTPHeaderField = "Content-Type")
        setValue(SecurityConstants.CLIENT_TOKEN, forHTTPHeaderField = SecurityConstants.CLIENT_HEADER)
        setValue(SecurityConstants.PLATFORM_IOS, forHTTPHeaderField = SecurityConstants.PLATFORM_HEADER)
        setHTTPBody(bodyBytes.toNSData())
    }

    val task = session.dataTaskWithRequest(request)
    task.resume()

    try {
        for (event in eventChannel) {
            onEvent(event)
        }
    } finally {
        task.cancel()
        session.finishTasksAndInvalidate()
    }
}

private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val bytes = ByteArray(size)
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this@toByteArray.bytes, this@toByteArray.length)
    }
    return bytes
}

/**
 * NSURLSession delegate that receives incremental data and parses SSE events.
 * Callbacks come on the main queue — buffer access is single-threaded.
 */
private class SseDataDelegate(
    private val eventChannel: Channel<SseEvent>,
) : NSObject(), NSURLSessionDataDelegateProtocol {

    private val buffer = StringBuilder()
    private var currentEvent = ""
    private var currentData = ""

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        buffer.append(didReceiveData.toByteArray().decodeToString())

        // Parse complete SSE lines from buffer
        while (true) {
            val idx = buffer.indexOf('\n')
            if (idx == -1) break
            val line = buffer.substring(0, idx).trimEnd()
            buffer.deleteRange(0, idx + 1)

            when {
                line.startsWith("event: ") -> currentEvent = line.removePrefix("event: ").trim()
                line.startsWith("data: ") -> currentData = line.removePrefix("data: ").trim()
                line.isEmpty() && currentData.isNotEmpty() -> {
                    eventChannel.trySend(SseEvent(currentEvent, currentData))
                    currentEvent = ""
                    currentData = ""
                }
            }
        }
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        if (didCompleteWithError != null) {
            println("SSE stream error: ${didCompleteWithError.localizedDescription}")
        }
        eventChannel.close()
    }
}
