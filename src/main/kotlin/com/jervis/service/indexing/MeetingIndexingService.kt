package com.jervis.service.indexing

import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Service for indexing meeting content from Whisper transcription service.
 * This service will be implemented when Whisper service is available.
 */
@Service
class MeetingIndexingService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Result of meeting indexing operation
     */
    data class MeetingIndexingResult(
        val processedMeetings: Int,
        val skippedMeetings: Int,
        val errorMeetings: Int,
    )

    /**
     * Index meeting transcript from Whisper service.
     * TODO: Implement when Whisper service is available
     */
    suspend fun indexMeetingFromWhisper(
        project: ProjectDocument,
        meetingId: String,
        transcript: String,
        meetingTitle: String,
        participantList: List<String> = emptyList(),
        timestamp: Instant = Instant.now(),
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                logger.info { "Indexing meeting transcript: $meetingTitle for project: ${project.name}" }

                if (transcript.isBlank()) {
                    logger.warn { "Empty transcript for meeting: $meetingTitle" }
                    return@withContext false
                }

                // Split transcript into chunks for better indexing
                val chunks = splitTranscriptIntoChunks(transcript)
                var processedChunks = 0

                for ((index, chunk) in chunks.withIndex()) {
                    try {
                        val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, chunk)

                        val ragDocument =
                            RagDocument(
                                projectId = project.id,
                                documentType = RagDocumentType.MEETING,
                                ragSourceType = RagSourceType.LLM, // From Whisper transcription
                                pageContent = chunk,
                                source = "whisper://$meetingId",
                                path = "meetings/$meetingTitle",
                                module = "meeting-transcript",
                                language = "transcript",
                                timestamp = timestamp.toEpochMilli(),
                            )

                        vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)
                        processedChunks++
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to index meeting chunk $index for meeting: $meetingTitle" }
                    }
                }

                logger.info { "Successfully indexed meeting: $meetingTitle with $processedChunks chunks" }
                return@withContext true
            } catch (e: Exception) {
                logger.error(e) { "Failed to index meeting: $meetingTitle" }
                return@withContext false
            }
        }

    /**
     * Index meeting notes manually (for meetings without Whisper transcription)
     */
    suspend fun indexMeetingNotes(
        project: ProjectDocument,
        meetingTitle: String,
        notes: String,
        timestamp: Instant = Instant.now(),
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                logger.info { "Indexing meeting notes: $meetingTitle for project: ${project.name}" }

                val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, notes)

                val ragDocument =
                    RagDocument(
                        projectId = project.id!!,
                        documentType = RagDocumentType.MEETING,
                        ragSourceType = RagSourceType.FILE, // From manual notes
                        pageContent = notes,
                        source = "manual://meeting-notes/$meetingTitle",
                        path = "meetings/$meetingTitle",
                        module = "meeting-notes",
                        language = "notes",
                        timestamp = timestamp.toEpochMilli(),
                    )

                vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)

                logger.info { "Successfully indexed meeting notes: $meetingTitle" }
                return@withContext true
            } catch (e: Exception) {
                logger.error(e) { "Failed to index meeting notes: $meetingTitle" }
                return@withContext false
            }
        }

    /**
     * Split transcript into manageable chunks for better indexing
     */
    private fun splitTranscriptIntoChunks(
        transcript: String,
        maxChunkSize: Int = 2000,
    ): List<String> {
        val sentences = transcript.split(Regex("[.!?]\\s+"))
        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()

        for (sentence in sentences) {
            if (currentChunk.length + sentence.length > maxChunkSize && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.toString().trim())
                currentChunk = StringBuilder()
            }
            currentChunk.append(sentence).append(". ")
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }

        return chunks.filter { it.isNotBlank() }
    }

    /**
     * Placeholder for future integration with Whisper service
     * TODO: Implement when Whisper service API is available
     */
    suspend fun connectToWhisperService(): Boolean {
        logger.info { "Whisper service integration not yet implemented" }
        return false
    }
}
