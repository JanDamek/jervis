package com.jervis.ui.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.time.Clock

actual object AudioChunkQueue {
    private val queueDir = File(System.getProperty("user.home"), ".jervis/audio_queue")
    private val indexFile = File(queueDir, "pending.json")

    actual fun enqueue(meetingId: String, chunkIndex: Int, data: ByteArray): PendingChunk {
        queueDir.mkdirs()
        val fileName = "${meetingId}_${chunkIndex}.wav"
        File(queueDir, fileName).writeBytes(data)
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
        val file = File(queueDir, chunk.fileName)
        return if (file.exists()) file.readBytes() else null
    }

    actual fun dequeue(chunk: PendingChunk) {
        File(queueDir, chunk.fileName).delete()
        val pending = loadIndex().toMutableList()
        pending.removeAll { it.meetingId == chunk.meetingId && it.chunkIndex == chunk.chunkIndex }
        saveIndex(pending)
    }

    actual fun getAllPending(): List<PendingChunk> = loadIndex()

    actual fun clearMeeting(meetingId: String) {
        val pending = loadIndex()
        pending.filter { it.meetingId == meetingId }.forEach { chunk ->
            File(queueDir, chunk.fileName).delete()
        }
        saveIndex(pending.filter { it.meetingId != meetingId })
    }

    private fun loadIndex(): List<PendingChunk> {
        if (!indexFile.exists()) return emptyList()
        return try {
            Json.decodeFromString<List<PendingChunk>>(indexFile.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveIndex(chunks: List<PendingChunk>) {
        queueDir.mkdirs()
        indexFile.writeText(Json.encodeToString(chunks))
    }
}
