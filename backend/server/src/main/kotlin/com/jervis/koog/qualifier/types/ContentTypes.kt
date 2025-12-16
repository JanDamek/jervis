package com.jervis.koog.qualifier.types

import com.jervis.entity.AttachmentMetadata
import kotlinx.serialization.Serializable

/**
 * Content type classification for appropriate processing strategy.
 *
 * Each content type requires different key information extraction and processing approach.
 */
enum class ContentType {
    /** Email message - requires sender, recipient, subject extraction */
    EMAIL,

    /** JIRA ticket - requires key (e.g., SDB-2080), status, assignee, epic, sprint */
    JIRA,

    /** Confluence page - requires author, title, topic classification */
    CONFLUENCE,

    /** Log file - requires summarization instead of chunking */
    LOG,

    /** Generic content - standard chunking and indexing */
    GENERIC,
}

/**
 * Vision context with two-stage analysis:
 * 1. General description of what's in the image
 * 2. Type-specific detailed extraction based on content type
 */
data class VisionContext(
    val originalText: String,
    val generalVisionSummary: String?, // Stage 1: General description
    val typeSpecificVisionDetails: String?, // Stage 2: Detailed extraction based on content type
    val attachments: List<AttachmentMetadata>,
)

/**
 * Content type detection result from Phase 1.
 */
data class ContentTypeContext(
    val contentType: ContentType,
    val originalText: String,
    val visionContext: VisionContext,
)

/**
 * Email-specific extracted information.
 */
data class EmailExtraction(
    val sender: String,
    val recipients: List<String>,
    val subject: String,
    val classification: String, // What is the email about (e.g., "Bug report", "Feature request", "Question")
    val content: String, // Full email body
)

/**
 * JIRA-specific extracted information.
 */
@Serializable
data class JiraExtraction(
    val key: String, // e.g., "SDB-2080"
    val status: String,
    val type: String, // Bug, Story, Task, etc.
    val assignee: String?,
    val reporter: String,
    val epic: String?,
    val sprint: String?,
    val changeDescription: String, // What changed in this update
    val classification: JiraClassification,
)

/**
 * JIRA classification for user task routing.
 */
enum class JiraClassification {
    /** Informational - no action needed */
    INFO,

    /** Requires user action - create user task */
    ACTIONABLE,

    /** Custom group classification (e.g., "critical-bugs", "sprint-blockers") */
    CUSTOM_GROUP,
}

/**
 * Confluence-specific extracted information.
 */
data class ConfluenceExtraction(
    val author: String,
    val title: String,
    val topic: String, // Main topic/focus of the page
    val content: String, // Full page content
)

/**
 * LOG-specific summarization (different from chunking).
 */
data class LogSummarization(
    val summary: String, // High-level summary of what happened
    val keyEvents: List<String>, // Main events/errors/warnings
    val criticalDetails: List<String>, // Important details to index
)

/**
 * Unified extraction result - one of the type-specific extractions.
 */
sealed class ExtractionResult {
    data class Email(val data: EmailExtraction) : ExtractionResult()

    data class Jira(val data: JiraExtraction) : ExtractionResult()

    data class Confluence(val data: ConfluenceExtraction) : ExtractionResult()

    data class Log(val data: LogSummarization) : ExtractionResult()

    data class Generic(val chunks: List<String>, val baseInfo: String) : ExtractionResult()
}

/**
 * Final context ready for unified indexing.
 * All type-specific information has been extracted and normalized.
 */
data class IndexingContext(
    val contentType: ContentType,
    val baseNodeKey: String,
    val baseInfo: String, // High-level summary for base node
    val indexableChunks: List<String>, // Normalized chunks ready for RAG+Graph
    val visionContext: VisionContext,
    val metadata: Map<String, String> = emptyMap(), // Type-specific metadata
)
