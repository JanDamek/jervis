package com.jervis.dto

enum class PendingTaskTypeEnum {
    EMAIL_PROCESSING,
    AGENT_ANALYSIS,
    COMMIT_ANALYSIS,

    /**
     * Analyze source file structure and create AI description.
     * Creates class/file description stored in RAG for future reference.
     * Used to build architectural knowledge without reading source code repeatedly.
     */
    FILE_STRUCTURE_ANALYSIS,

    /**
     * Update project short and full descriptions based on recent commits.
     * Uses file descriptions from RAG, not source code (avoids context limits).
     */
    PROJECT_DESCRIPTION_UPDATE,

    /**
     * Delayed response to user question.
     * Used when agent can't answer immediately (e.g., branch not indexed yet).
     * Task waits for condition to be met, then agent answers original question.
     */
    DELAYED_USER_RESPONSE,

    /**
     * Jira configuration/setup actions were previously used to enable indexing.
     * Note: Configuration must be performed during user-guided desktop/server setup; not used for background tasks.
     */
    JIRA_CONFIG,

    /**
     * Jira synchronization and deep indexing tasks.
     */
    JIRA_SYNC,

    /**
     * Confluence documentation page analysis.
     * Analyzes page content, extracts key concepts, creates structured summary,
     * links to related code/commits/emails, identifies action items.
     */
    CONFLUENCE_PAGE_ANALYSIS,

    /**
     * Manual review of a link that could not be indexed automatically.
     * Created when cross-indexer URL handoff fails after max retry attempts.
     * User should review and decide: add to safe patterns, ignore, or index manually.
     */
    LINK_REVIEW,

    /**
     * Link safety qualification is UNCERTAIN - needs agent review.
     * Created when LLM cannot determine if link is safe (no clear patterns).
     * Agent analyzes link + context and decides:
     * - SAFE → indexes the link via MCP tool
     * - UNSAFE → adds pattern to blacklist
     * - SKIP → marks as reviewed but don't index
     *
     * Task content includes:
     * - URL
     * - Text context before/after link
     * - Source (email, Jira, Confluence)
     * - LLM reasoning for UNCERTAIN decision
     */
    LINK_SAFETY_REVIEW,
}
