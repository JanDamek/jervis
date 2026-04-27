package com.jervis.ui.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy
import kotlin.time.Clock

@OptIn(ExperimentalForeignApi::class)
actual object AudioChunkQueue {
    private val queueDirPath: String
        get() {
            @Suppress("UNCHECKED_CAST")
            val docs = NSSearchPathForDirectoriesInDomains(
                NSDocumentDirectory, NSUserDomainMask, true,
            ).firstOrNull() as? String ?: ""
            return "$docs/audio_queue"
        }

    private val indexFilePath: String get() = "$queueDirPath/pending.json"

    actual fun enqueue(meetingId: String, chunkIndex: Int, data: ByteArray): PendingChunk {
        NSFileManager.defaultManager.createDirectoryAtPath(queueDirPath, true, null, null)
        val fileName = "${meetingId}_${chunkIndex}.wav"
        val filePath = "$queueDirPath/$fileName"
        data.usePinned { pinned ->
            val nsData = NSData.create(bytes = pinned.addressOf(0), length = data.size.toULong())
            nsData.writeToFile(filePath, true)
        }
        val chunk = PendingChunk(
            meetingId = meetingId,
            chunkIndex = chunkIndex,
            fileName = fileName,
            createdAtMs = Clock.System.now().toEpochMilliseconds(),
        )
        val pending = loadIndex().toMutableList()
        pending.removeAll { it.meetingId == meetingId && it.chunkIndex == chunkIndex }
        pending.add(chunk)
        saveIndex(pending)
        return chunk
    }

    actual fun readChunk(chunk: PendingChunk): ByteArray? {
        val filePath = "$queueDirPath/${chunk.fileName}"
        val nsData = NSData.dataWithContentsOfFile(filePath) ?: return null
        val size = nsData.length.toInt()
        if (size == 0) return ByteArray(0)
        val bytes = ByteArray(size)
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), nsData.bytes, nsData.length)
        }
        return bytes
    }

    actual fun dequeue(chunk: PendingChunk) {
        val filePath = "$queueDirPath/${chunk.fileName}"
        NSFileManager.defaultManager.removeItemAtPath(filePath, null)
        val pending = loadIndex().toMutableList()
        pending.removeAll { it.meetingId == chunk.meetingId && it.chunkIndex == chunk.chunkIndex }
        saveIndex(pending)
    }

    actual fun getAllPending(): List<PendingChunk> = loadIndex()

    actual fun clearMeeting(meetingId: String) {
        val pending = loadIndex()
        pending.filter { it.meetingId == meetingId }.forEach { chunk ->
            NSFileManager.defaultManager.removeItemAtPath("$queueDirPath/${chunk.fileName}", null)
        }
        saveIndex(pending.filter { it.meetingId != meetingId })
    }

    private fun loadIndex(): List<PendingChunk> {
        val json = NSString.stringWithContentsOfFile(indexFilePath, NSUTF8StringEncoding, null)
            ?: return emptyList()
        return try {
            Json.decodeFromString<List<PendingChunk>>(json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Suppress("CAST_NEVER_SUCCEEDS")
    private fun saveIndex(chunks: List<PendingChunk>) {
        NSFileManager.defaultManager.createDirectoryAtPath(queueDirPath, true, null, null)
        val json = Json.encodeToString(chunks)
        (json as NSString).writeToFile(indexFilePath, true, NSUTF8StringEncoding, null)
    }
}
