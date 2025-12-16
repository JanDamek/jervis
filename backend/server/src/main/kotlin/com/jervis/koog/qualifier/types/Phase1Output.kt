package com.jervis.koog.qualifier.types

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/**
 * Content type detection result from Phase 1.
 * LLM determines what type of content this is to select appropriate extraction strategy.
 */
@Serializable
@LLMDescription("Content type detection result")
data class ContentTypeDetection(
    @LLMDescription(
        """
Content type classification:
- EMAIL: Email message
- JIRA: JIRA ticket/issue
- CONFLUENCE: Confluence page
- LOG: Log file (requires summarization, not chunking)
- GENERIC: Generic text content
        """,
    )
    val contentType: String, // EMAIL, JIRA, CONFLUENCE, LOG, GENERIC
    @LLMDescription("Brief explanation why you classified it as this type")
    val reason: String,
)

/**
 * Email-specific extraction output.
 * LLM extracts structured information from email content.
 */
@Serializable
@LLMDescription("Email information extraction")
data class EmailExtractionOutput(
    @LLMDescription("Email sender address or name")
    val sender: String,
    @LLMDescription("List of recipient addresses or names")
    val recipients: List<String>,
    @LLMDescription("Email subject line")
    val subject: String,
    @LLMDescription("Classification of email content (e.g., 'Bug report', 'Feature request', 'Question')")
    val classification: String,
)

/**
 * JIRA-specific extraction output.
 * LLM extracts structured information from JIRA ticket.
 */
@Serializable
@LLMDescription("JIRA ticket information extraction")
data class JiraExtractionOutput(
    @LLMDescription("JIRA ticket key (e.g., 'SDB-2080')")
    val key: String,
    @LLMDescription("Ticket status (e.g., 'Open', 'In Progress', 'Done')")
    val status: String,
    @LLMDescription("Ticket type (e.g., 'Bug', 'Story', 'Task')")
    val type: String,
    @LLMDescription("Assignee name (or 'Unassigned')")
    val assignee: String,
    @LLMDescription("Reporter name")
    val reporter: String,
    @LLMDescription("Epic name or key (or null if not in epic)")
    val epic: String?,
    @LLMDescription("Sprint name (or null if not in sprint)")
    val sprint: String?,
    @LLMDescription("Brief description of what changed in this update")
    val changeDescription: String,
)

/**
 * Confluence-specific extraction output.
 * LLM extracts structured information from Confluence page.
 */
@Serializable
@LLMDescription("Confluence page information extraction")
data class ConfluenceExtractionOutput(
    @LLMDescription("Page author name")
    val author: String,
    @LLMDescription("Page title")
    val title: String,
    @LLMDescription("Main topic or focus of the page")
    val topic: String,
)

/**
 * LOG summarization output (different from chunking).
 * For logs, we don't chunk - we summarize key events and details.
 */
@Serializable
@LLMDescription("LOG file summarization")
data class LogSummarizationOutput(
    @LLMDescription("High-level summary of what happened in the log")
    val summary: String,
    @LLMDescription("List of key events, errors, or warnings")
    val keyEvents: List<String>,
    @LLMDescription("List of critical details to index (error codes, service names, timestamps)")
    val criticalDetails: List<String>,
)

/**
 * Generic content chunking output.
 * For generic content, we use standard semantic chunking.
 */
@Serializable
@LLMDescription("Generic content semantic chunking")
data class GenericChunkingOutput(
    @LLMDescription("High-level summary of document content (1-3 sentences)")
    val baseInfo: String,
    @LLMDescription(
        """
List of semantic text chunks from the document.
Each chunk should be 500-3000 tokens, semantically coherent.
Copy text from document, do not summarize.
        """,
    )
    val chunks: List<String>,
)
