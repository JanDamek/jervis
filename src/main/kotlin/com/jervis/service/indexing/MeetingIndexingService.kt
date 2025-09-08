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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.pathString

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
     * Index audio files from meeting path directory
     * Creates placeholder entries for audio files until transcription is available
     */
    suspend fun indexMeetingAudioFiles(
        project: ProjectDocument,
        meetingPath: Path,
    ): MeetingIndexingResult =
        withContext(Dispatchers.Default) {
            try {
                logger.info { "Scanning meeting audio files in: ${meetingPath.pathString} for project: ${project.name}" }

                if (!Files.exists(meetingPath) || !Files.isDirectory(meetingPath)) {
                    logger.warn { "Meeting path does not exist or is not a directory: ${meetingPath.pathString}" }
                    return@withContext MeetingIndexingResult(0, 0, 0)
                }

                val audioExtensions = setOf("mp3", "wav", "m4a", "flac", "ogg", "aac", "wma")
                var processedFiles = 0
                var errorFiles = 0

                // Find all audio files in the meeting directory
                val audioFiles =
                    Files
                        .walk(meetingPath)
                        .filter { it.isRegularFile() }
                        .filter { audioExtensions.contains(it.extension.lowercase()) }
                        .toList()

                // Process each audio file
                for (audioFile in audioFiles) {
                    try {
                        val success = indexAudioFilePlaceholder(project, audioFile, meetingPath)
                        if (success) {
                            processedFiles++
                        } else {
                            errorFiles++
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to index audio file: ${audioFile.pathString}" }
                        errorFiles++
                    }
                }

                val result = MeetingIndexingResult(processedFiles, 0, errorFiles)
                logger.info {
                    "Meeting audio file indexing completed for project: ${project.name} - " +
                        "Processed: $processedFiles, Errors: $errorFiles"
                }

                result
            } catch (e: Exception) {
                logger.error(e) { "Error during meeting audio file indexing for project: ${project.name}" }
                MeetingIndexingResult(0, 0, 1)
            }
        }

    /**
     * Create placeholder index entry for audio file until transcription is available
     */
    private suspend fun indexAudioFilePlaceholder(
        project: ProjectDocument,
        audioFile: Path,
        meetingBasePath: Path,
    ): Boolean {
        try {
            val fileName = audioFile.name
            val relativePath = meetingBasePath.relativize(audioFile).pathString
            val fileStats = Files.readAttributes(audioFile, BasicFileAttributes::class.java)

            val placeholderContent =
                buildString {
                    appendLine("Meeting Audio File: $fileName")
                    appendLine("=".repeat(60))
                    appendLine("Project: ${project.name}")
                    appendLine("File Path: $relativePath")
                    appendLine("File Size: ${fileStats.size()} bytes")
                    appendLine("Created: ${fileStats.creationTime()}")
                    appendLine("Modified: ${fileStats.lastModifiedTime()}")
                    appendLine("Format: ${audioFile.extension.uppercase()}")
                    appendLine()
                    appendLine("Status: Audio file available for meeting content")
                    appendLine("Note: This is a placeholder entry for the audio file.")
                    appendLine("Actual meeting content will be available when audio transcription is processed.")
                    appendLine()
                    appendLine("This meeting audio file contains:")
                    appendLine("- Recorded meeting discussion and decisions")
                    appendLine("- Project-related conversations and planning")
                    appendLine("- Technical discussions and requirements")
                    appendLine("- Action items and follow-up tasks")
                    appendLine()
                    appendLine("To access meeting content:")
                    appendLine("1. Audio file is located at: $relativePath")
                    appendLine("2. File can be processed through Whisper transcription service")
                    appendLine("3. Transcribed content will be indexed for full-text search")
                    appendLine()
                    appendLine("---")
                    appendLine("Generated by: Meeting Audio File Indexing")
                    appendLine("Type: Audio File Placeholder")
                    appendLine("Project: ${project.name}")
                    appendLine("Indexed for: RAG search and meeting discovery")
                }

            val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, placeholderContent)

            val ragDocument =
                RagDocument(
                    projectId = project.id!!,
                    documentType = RagDocumentType.MEETING,
                    ragSourceType = RagSourceType.FILE,
                    pageContent = placeholderContent,
                    source = "audio://${project.name}/$relativePath",
                    path = relativePath,
                    module = "meeting-audio",
                    language = "audio-placeholder",
                    timestamp = fileStats.lastModifiedTime().toMillis(),
                )

            vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)
            logger.debug { "Successfully indexed audio file placeholder: $fileName" }
            return true
        } catch (e: Exception) {
            logger.error(e) { "Failed to create audio file placeholder: ${audioFile.pathString}" }
            return false
        }
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
