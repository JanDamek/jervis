package com.jervis.domain.task

/**
 * Unified source type for all tasks (background pending tasks, user tasks, scheduled tasks).
 * Clearly indicates where the task originated from.
 */
enum class TaskSourceType {
    /**
     * Task created from incoming email
     */
    EMAIL,

    /**
     * Task created from meeting transcript/notes
     */
    MEETING,

    /**
     * Task created from git commit analysis
     */
    GIT_COMMIT,

    /**
     * Task suggested by AI agent analysis
     */
    AGENT_SUGGESTION,

    /**
     * Task manually created by user
     */
    MANUAL,

    /**
     * Task created from external system (Jira, Slack, etc.)
     */
    EXTERNAL_SYSTEM,

    /**
     * Task created for knowledge rule approval (Knowledge Engine)
     */
    KNOWLEDGE_APPROVAL,
}
