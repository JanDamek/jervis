package com.jervis.service.indexing

import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * Minimal audio transcript indexing service placeholder.
 * Accepts uploaded audio files and logs that a transcription job was enqueued.
 * Actual transcription and RAG indexing can be implemented later and wired here.
 */
@Service
class AudioTranscriptIndexingService {
    private val logger = KotlinLogging.logger {}

    data class TranscriptionJob(
        val fileName: String,
        val filePath: Path,
        val source: String,
    )

    suspend fun enqueueTranscription(job: TranscriptionJob) {
        // For now, just log; a real implementation would dispatch a background job.
        logger.info { "Enqueued transcription job for ${'$'}{job.source}: ${'$'}{job.fileName} at ${'$'}{job.filePath}" }
    }
}
