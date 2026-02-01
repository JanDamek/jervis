package com.jervis.koog.qualifier.types

import com.jervis.domain.atlassian.AttachmentMetadata
import kotlinx.serialization.Serializable

/**
 * Content type classification for appropriate processing strategy.
 *
 * Each content type requires different key information extraction and processing approach.
 *
 * Note: These are GENERIC types - specific implementations (Jira, GitHub, Confluence, etc.)
 * are handled by connection-specific services via microservices.
 */
enum class ContentType {
    EMAIL,
    BUGTRACKER_ISSUE,  // Generic: Jira, GitHub Issues, GitLab Issues, etc.
    WIKI_PAGE,         // Generic: Confluence, MediaWiki, Notion, etc.
    LOG,
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
 * Bug tracker issue extracted information.
 * Generic structure for Jira, GitHub Issues, GitLab Issues, etc.
 */
@Serializable
data class BugTrackerIssueExtraction(
    val key: String, // e.g., "SDB-2080", "GH-123", "GL-456"
    val status: String,
    val type: String, // Bug, Story, Task, Enhancement, etc.
    val assignee: String?,
    val reporter: String,
    val parentIssue: String?, // Epic in Jira, parent issue in GitHub/GitLab
    val milestone: String?,   // Sprint in Jira, milestone in GitHub/GitLab
    val changeDescription: String, // What changed in this update
    val classification: IssueClassification,
)

/**
 * Issue classification for user task routing.
 * Applies to all bug tracker systems.
 */
enum class IssueClassification {
    /** Informational - no action needed */
    INFO,

    /** Requires user action - create user task */
    ACTIONABLE,

    /** Custom group classification (e.g., "critical-bugs", "sprint-blockers") */
    CUSTOM_GROUP,
}

/**
 * Wiki page extracted information.
 * Generic structure for Confluence, MediaWiki, Notion, etc.
 */
data class WikiPageExtraction(
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
    data class Email(
        val data: EmailExtraction,
    ) : ExtractionResult()

    data class BugTrackerIssue(
        val data: BugTrackerIssueExtraction,
    ) : ExtractionResult()

    data class WikiPage(
        val data: WikiPageExtraction,
    ) : ExtractionResult()

    data class Log(
        val data: LogSummarization,
    ) : ExtractionResult()

    data class Generic(
        val chunks: List<String>,
        val baseInfo: String,
    ) : ExtractionResult()
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
