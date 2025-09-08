package com.jervis.service.indexing

import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.rag.RagIndexingStatusService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString

/**
 * Service for indexing meeting transcripts and related meeting content.
 * Processes various meeting formats including transcripts, notes, and recordings.
 */
@Service
class MeetingTranscriptIndexingService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val ragIndexingStatusService: RagIndexingStatusService,
    private val historicalVersioningService: HistoricalVersioningService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Result of meeting transcript indexing operation
     */
    data class MeetingTranscriptIndexingResult(
        val processedTranscripts: Int,
        val skippedTranscripts: Int,
        val errorTranscripts: Int,
    )

    /**
     * Meeting transcript metadata extracted from content or filename
     */
    data class MeetingMetadata(
        val title: String,
        val date: LocalDateTime?,
        val participants: List<String> = emptyList(),
        val duration: String? = null,
        val meetingType: String = "meeting",
    )

    /**
     * Index all meeting transcripts for a project
     */
    suspend fun indexProjectMeetingTranscripts(project: ProjectDocument): MeetingTranscriptIndexingResult =
        withContext(Dispatchers.IO) {
            logger.info { "Starting meeting transcript indexing for project: ${project.name}" }

            val meetingPath = project.meetingPath
            if (meetingPath.isNullOrBlank()) {
                logger.info { "No meeting path configured for project: ${project.name}" }
                return@withContext MeetingTranscriptIndexingResult(0, 0, 0)
            }

            val meetingDir = Paths.get(meetingPath)
            if (!Files.exists(meetingDir)) {
                logger.warn { "Meeting path does not exist: $meetingPath" }
                return@withContext MeetingTranscriptIndexingResult(0, 0, 1)
            }

            // Get current git commit hash for tracking
            val gitCommitHash =
                historicalVersioningService.getCurrentGitCommitHash(Paths.get(project.path))
                    ?: "meetings-${System.currentTimeMillis()}"

            var processedTranscripts = 0
            var skippedTranscripts = 0
            var errorTranscripts = 0

            try {
                val meetingFiles = mutableListOf<Path>()

                Files
                    .walk(meetingDir)
                    .filter { it.isRegularFile() }
                    .filter { isMeetingFile(it) }
                    .forEach { meetingFiles.add(it) }

                logger.info { "Found ${meetingFiles.size} meeting files to process" }

                for (meetingFile in meetingFiles) {
                    try {
                        val content = Files.readString(meetingFile)
                        if (content.isBlank()) {
                            skippedTranscripts++
                            continue
                        }

                        val relativePath = meetingDir.relativize(meetingFile).toString()

                        // Check if already indexed to prevent duplicates
                        val shouldIndex =
                            ragIndexingStatusService.shouldIndexFile(
                                projectId = project.id,
                                filePath = "meetings/$relativePath",
                                gitCommitHash = gitCommitHash,
                                fileContent = content.toByteArray(),
                            )

                        if (!shouldIndex) {
                            skippedTranscripts++
                            logger.debug { "Skipping already indexed meeting file: $relativePath" }
                            continue
                        }

                        // Track indexing status
                        ragIndexingStatusService.startIndexing(
                            projectId = project.id,
                            filePath = "meetings/$relativePath",
                            gitCommitHash = gitCommitHash,
                            ragSourceType = RagSourceType.FILE,
                            fileContent = content.toByteArray(),
                            language = inferMeetingFormat(meetingFile),
                            module = "meetings",
                        )

                        // Extract meeting metadata
                        val metadata = extractMeetingMetadata(meetingFile, content)

                        // Create RAG document
                        val ragDocument =
                            RagDocument(
                                projectId = project.id,
                                documentType = RagDocumentType.MEETING,
                                ragSourceType = RagSourceType.FILE,
                                pageContent = buildMeetingContent(meetingFile, content, metadata),
                                source = "meeting://${project.name}/$relativePath",
                                path = relativePath,
                                module = "meetings",
                                language = inferMeetingFormat(meetingFile),
                                gitCommitHash = gitCommitHash,
                            )

                        // Generate embedding and store
                        val embedding =
                            embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, ragDocument.pageContent)
                        vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)

                        processedTranscripts++
                        logger.debug { "Successfully indexed meeting file: $relativePath" }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to index meeting file: ${meetingFile.pathString}" }
                        errorTranscripts++
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error during meeting transcript indexing for project: ${project.name}" }
                errorTranscripts++
            }

            logger.info {
                "Meeting transcript indexing completed for project: ${project.name} - " +
                    "Processed: $processedTranscripts, Skipped: $skippedTranscripts, Errors: $errorTranscripts"
            }

            MeetingTranscriptIndexingResult(processedTranscripts, skippedTranscripts, errorTranscripts)
        }

    /**
     * Check if file is a meeting-related file based on extension and name patterns
     */
    private fun isMeetingFile(path: Path): Boolean {
        val fileName = path.fileName.toString().lowercase()
        val extension = fileName.substringAfterLast('.', "")

        // Check for common meeting file extensions
        val meetingExtensions = setOf("txt", "md", "docx", "doc", "pdf", "vtt", "srt", "json", "xml")
        if (extension !in meetingExtensions) return false

        // Check for meeting-related keywords in filename
        val meetingKeywords =
            setOf(
                "meeting",
                "transcript",
                "notes",
                "minutes",
                "standup",
                "scrum",
                "retrospective",
                "planning",
                "review",
                "demo",
                "call",
                "conference",
                "discussion",
                "session",
                "workshop",
                "briefing",
            )

        return meetingKeywords.any { keyword -> fileName.contains(keyword) }
    }

    /**
     * Extract meeting metadata from file content and filename
     */
    private fun extractMeetingMetadata(
        meetingFile: Path,
        content: String,
    ): MeetingMetadata {
        val fileName = meetingFile.fileName.toString()

        // Try to extract date from filename
        val dateFromFilename = extractDateFromFilename(fileName)

        // Try to extract participants from content
        val participants = extractParticipants(content)

        // Try to extract meeting title
        val title = extractMeetingTitle(fileName, content)

        // Try to extract duration
        val duration = extractDuration(content)

        // Determine meeting type
        val meetingType = determineMeetingType(fileName, content)

        return MeetingMetadata(
            title = title,
            date = dateFromFilename,
            participants = participants,
            duration = duration,
            meetingType = meetingType,
        )
    }

    /**
     * Extract date from filename using common patterns
     */
    private fun extractDateFromFilename(fileName: String): LocalDateTime? {
        listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy_MM_dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("MM-dd-yyyy"),
        )

        // Look for date patterns in filename
        val dateRegexes =
            listOf(
                "\\d{4}-\\d{2}-\\d{2}".toRegex(),
                "\\d{4}_\\d{2}_\\d{2}".toRegex(),
                "\\d{8}".toRegex(),
                "\\d{2}-\\d{2}-\\d{4}".toRegex(),
                "\\d{2}-\\d{2}-\\d{4}".toRegex(),
            )

        for ((_, regex) in dateRegexes.withIndex()) {
            val match = regex.find(fileName)
            if (match != null) {
                try {
                    val dateStr = match.value
                    val date = LocalDateTime.parse(dateStr + "T00:00:00", DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    return date
                } catch (e: DateTimeParseException) {
                    // Try next pattern
                    continue
                }
            }
        }

        return null
    }

    /**
     * Extract participants from meeting content
     */
    private fun extractParticipants(content: String): List<String> {
        val participants = mutableSetOf<String>()

        // Look for common participant patterns
        val participantPatterns =
            listOf(
                "(?i)participants?:\\s*(.+)".toRegex(),
                "(?i)attendees?:\\s*(.+)".toRegex(),
                "(?i)present:\\s*(.+)".toRegex(),
                "(?i)speakers?:\\s*(.+)".toRegex(),
                "^([A-Z][a-z]+ [A-Z][a-z]+):\\s*".toRegex(RegexOption.MULTILINE),
                "^([A-Z][a-z]+):\\s*".toRegex(RegexOption.MULTILINE),
            )

        for (pattern in participantPatterns) {
            val matches = pattern.findAll(content)
            for (match in matches) {
                val participantText = match.groupValues.getOrNull(1) ?: continue
                // Split by common separators
                val names =
                    participantText
                        .split("[,;|&]".toRegex())
                        .map { it.trim() }
                        .filter { it.isNotBlank() && it.length > 2 }
                participants.addAll(names)
            }
        }

        return participants.take(10).toList() // Limit to avoid noise
    }

    /**
     * Extract meeting title from filename or content
     */
    private fun extractMeetingTitle(
        fileName: String,
        content: String,
    ): String {
        // Try to extract title from content first
        val titlePatterns =
            listOf(
                "(?i)^#\\s*(.+)$".toRegex(RegexOption.MULTILINE),
                "(?i)title:\\s*(.+)".toRegex(),
                "(?i)subject:\\s*(.+)".toRegex(),
                "(?i)meeting:\\s*(.+)".toRegex(),
            )

        for (pattern in titlePatterns) {
            val match = pattern.find(content)
            if (match != null) {
                val title = match.groupValues[1].trim()
                if (title.isNotBlank()) return title
            }
        }

        // Fall back to cleaned filename
        return fileName
            .substringBeforeLast('.')
            .replace("[_-]".toRegex(), " ")
            .replace("(?i)(meeting|transcript|notes)".toRegex(), "")
            .trim()
            .takeIf { it.isNotBlank() } ?: "Meeting"
    }

    /**
     * Extract meeting duration from content
     */
    private fun extractDuration(content: String): String? {
        val durationPatterns =
            listOf(
                "(?i)duration:\\s*(\\d+[hm\\s]+)".toRegex(),
                "(?i)length:\\s*(\\d+[hm\\s]+)".toRegex(),
                "(?i)lasted:\\s*(\\d+[hm\\s]+)".toRegex(),
            )

        for (pattern in durationPatterns) {
            val match = pattern.find(content)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }

        return null
    }

    /**
     * Determine meeting type from filename and content
     */
    private fun determineMeetingType(
        fileName: String,
        content: String,
    ): String {
        val text = "$fileName $content".lowercase()

        return when {
            text.contains("standup") || text.contains("daily") -> "standup"
            text.contains("retrospective") || text.contains("retro") -> "retrospective"
            text.contains("planning") -> "planning"
            text.contains("review") -> "review"
            text.contains("demo") -> "demo"
            text.contains("scrum") -> "scrum"
            text.contains("interview") -> "interview"
            text.contains("workshop") -> "workshop"
            text.contains("training") -> "training"
            text.contains("briefing") -> "briefing"
            else -> "meeting"
        }
    }

    /**
     * Infer meeting file format from extension
     */
    private fun inferMeetingFormat(path: Path): String {
        val fileName = path.fileName.toString().lowercase()
        return when {
            fileName.endsWith(".md") -> "markdown"
            fileName.endsWith(".txt") -> "text"
            fileName.endsWith(".vtt") -> "vtt-transcript"
            fileName.endsWith(".srt") -> "srt-transcript"
            fileName.endsWith(".json") -> "json-transcript"
            fileName.endsWith(".xml") -> "xml-transcript"
            fileName.endsWith(".docx") || fileName.endsWith(".doc") -> "document"
            fileName.endsWith(".pdf") -> "pdf"
            else -> "meeting-notes"
        }
    }

    /**
     * Build formatted content for meeting documents
     */
    private fun buildMeetingContent(
        meetingFile: Path,
        content: String,
        metadata: MeetingMetadata,
    ): String =
        buildString {
            appendLine("Meeting: ${metadata.title}")
            appendLine("=".repeat(60))
            appendLine("File: ${meetingFile.pathString}")
            appendLine("Type: ${metadata.meetingType}")
            appendLine("Format: ${inferMeetingFormat(meetingFile)}")

            metadata.date?.let { date ->
                appendLine("Date: ${date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
            }

            metadata.duration?.let { duration ->
                appendLine("Duration: $duration")
            }

            if (metadata.participants.isNotEmpty()) {
                appendLine("Participants: ${metadata.participants.joinToString(", ")}")
            }

            appendLine()
            appendLine("Content:")
            appendLine(content)
            appendLine()
            appendLine("---")
            appendLine("Source: Meeting Transcript/Notes")
            appendLine("Indexed as: Meeting Content")
            appendLine("Searchable by: meeting type, participants, date, content")
        }
}
