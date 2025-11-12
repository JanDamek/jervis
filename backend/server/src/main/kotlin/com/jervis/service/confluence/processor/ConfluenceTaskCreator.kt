package com.jervis.service.confluence.processor

import com.jervis.domain.task.PendingTaskTypeEnum
import com.jervis.entity.ConfluencePageDocument
import com.jervis.service.background.PendingTaskService
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Creates PendingTasks for Confluence pages that need GPU analysis.
 *
 * Task Types:
 * - CONFLUENCE_PAGE_ANALYSIS: Analyze page content, extract key information, create summary
 *
 * GPU Analysis Goals (from YAML):
 * - Identify page type (API docs, architecture, tutorial, meeting notes, etc.)
 * - Extract key concepts and their relationships
 * - Create structured summary with project context
 * - Link to related code/commits/emails via RAG search
 * - Identify action items or decisions
 * - Create cross-references to other documentation
 *
 * Qualification:
 * - Qualifier (CPU) decides if page needs deep analysis
 * - Simple changelogs, auto-generated pages → DISCARD
 * - Important design docs, architecture, decisions → DELEGATE to GPU
 */
@Service
class ConfluenceTaskCreator(
    private val pendingTaskService: PendingTaskService,
) {
    /**
     * Create analysis task for a Confluence page.
     * Called after successful content indexing.
     */
    suspend fun createPageAnalysisTask(
        page: ConfluencePageDocument,
        plainText: String,
    ) {
        logger.info { "Creating analysis task for Confluence page: ${page.title} (${page.pageId})" }

        // Everything in content - simple and clear
        val taskContent =
            buildString {
                appendLine("Confluence Page Analysis Required")
                appendLine()
                appendLine("Page: ${page.title}")
                appendLine("Space: ${page.spaceKey}")
                appendLine("Page ID: ${page.pageId}")
                appendLine("URL: ${page.url}")
                appendLine("Version: ${page.lastKnownVersion}")
                appendLine("Last Modified: ${page.lastModifiedAt ?: "Unknown"} by ${page.lastModifiedBy ?: "Unknown"}")
                appendLine("Source: confluence://${page.accountId.toHexString()}/${page.pageId}")

                page.parentPageId?.let { appendLine("Parent Page ID: $it") }

                appendLine()
                appendLine("Links:")
                appendLine("- Internal links: ${page.internalLinks.size}")
                if (page.internalLinks.isNotEmpty()) {
                    appendLine("  ${page.internalLinks.joinToString(", ")}")
                }
                appendLine("- External links: ${page.externalLinks.size}")
                if (page.externalLinks.isNotEmpty()) {
                    appendLine("  ${page.externalLinks.take(10).joinToString(", ")}")
                }
                appendLine("- Child pages: ${page.childPageIds.size}")

                appendLine()
                appendLine("=== PAGE CONTENT ===")
                appendLine(plainText.take(10000)) // Limit content for task creation (full content in RAG)
                if (plainText.length > 10000) {
                    appendLine()
                    appendLine("[... content truncated, full text available via RAG search ...]")
                }
                appendLine("=== END PAGE CONTENT ===")
            }

        // Create pending task
        try {
            pendingTaskService.createTask(
                taskType = PendingTaskTypeEnum.CONFLUENCE_PAGE_ANALYSIS,
                content = taskContent,
                clientId = page.clientId,
                projectId = page.projectId,
                sourceUri = "confluence://${page.accountId.toHexString()}/${page.pageId}",
            )

            logger.info {
                "Created CONFLUENCE_PAGE_ANALYSIS task for page ${page.pageId} (${page.title})"
            }
        } catch (e: Exception) {
            logger.error(e) {
                "Failed to create analysis task for page ${page.pageId}: ${e.message}"
            }
            // Don't fail page indexing if task creation fails
        }
    }

    /**
     * Determine if page needs deep GPU analysis based on heuristics.
     * This is a pre-filter before qualification.
     *
     * Skip analysis for:
     * - Very short pages (< 200 chars)
     * - Auto-generated changelogs
     * - System pages
     */
    fun shouldAnalyzePage(
        page: ConfluencePageDocument,
        plainText: String,
    ): Boolean {
        // Skip very short pages
        if (plainText.length < 200) {
            logger.debug { "Skipping analysis for short page: ${page.title}" }
            return false
        }

        // Skip common auto-generated titles
        val skipTitles =
            listOf(
                "changelog",
                "release notes",
                "version history",
                "what's new",
            )

        if (skipTitles.any { page.title.lowercase().contains(it) }) {
            logger.debug { "Skipping analysis for auto-generated page: ${page.title}" }
            return false
        }

        // Analyze all other pages
        return true
    }
}
