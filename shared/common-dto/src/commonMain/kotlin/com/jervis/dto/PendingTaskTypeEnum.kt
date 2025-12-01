package com.jervis.dto

import com.jervis.configuration.prompts.PromptTypeEnum

enum class PendingTaskTypeEnum(
    val promptType: PromptTypeEnum,
) {
    EMAIL_PROCESSING(PromptTypeEnum.EMAIL_QUALIFIER),

    /**
     * Agent-created task for deeper investigation.
     * Agent discovered something requiring focused analysis.
     * Bypasses qualification - goes directly to GPU for execution.
     * Task content contains specific analysis instructions.
     */
    AGENT_ANALYSIS(PromptTypeEnum.QUALIFIER),

    /**
     * Scheduled task dispatched 10 minutes before scheduled time.
     * Bypasses qualification - goes directly to GPU for execution.
     * Used for: reminders, scheduled emails/calls, maintenance tasks, recurring actions.
     * Task content contains scheduled action instructions and original scheduled time.
     */
    SCHEDULED_TASK(PromptTypeEnum.QUALIFIER),

    COMMIT_ANALYSIS(PromptTypeEnum.QUALIFIER),

    /**
     * Analyze source file structure and create AI description.
     * Creates class/file description stored in RAG for future reference.
     * Used to build architectural knowledge without reading source code repeatedly.
     */
    FILE_STRUCTURE_ANALYSIS(PromptTypeEnum.QUALIFIER),

    /**
     * Update project short and full descriptions based on recent commits.
     * Uses file descriptions from RAG, not source code (avoids context limits).
     */
    PROJECT_DESCRIPTION_UPDATE(PromptTypeEnum.QUALIFIER),

    /**
     * Confluence documentation page analysis.
     * Analyzes page content, extracts key concepts, creates structured summary,
     * links to related code/commits/emails, identifies action items.
     */
    CONFLUENCE_PAGE_ANALYSIS(PromptTypeEnum.QUALIFIER),

    /**
     * Manual review of a link that could not be indexed automatically.
     * Created when cross-indexer URL handoff fails after max retry attempts.
     * User should review and decide: add to safe patterns, ignore, or index manually.
     */
    LINK_REVIEW(PromptTypeEnum.LINK_QUALIFIER),

    /**
     * Jira issue assigned to current user - needs qualification and analysis.
     * Contains issue summary, description, comments, and context.
     * Qualifies whether issue requires action from user.
     */
    JIRA_PROCESSING(PromptTypeEnum.JIRA_QUALIFIER),

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
    LINK_SAFETY_REVIEW(PromptTypeEnum.LINK_QUALIFIER),

    /**
     * Connection authentication error requiring manual fix.
     * Created when connection fails during polling with authentication error (invalid credentials).
     * User should check and update connection credentials.
     * Connection is set to INVALID state until fixed.
     */
    CONNECTION_ERROR(PromptTypeEnum.QUALIFIER),
}
