package com.jervis.service.indexing.jira.state

/**
 * Indexing state for Jira issues.
 * Tracks the lifecycle from discovery to indexing completion.
 */
enum class JiraIssueState {
    /** Issue discovered from Jira API, not yet indexed */
    NEW,

    /** Currently being indexed (prevents concurrent processing) */
    INDEXING,

    /** Successfully indexed to RAG */
    INDEXED,

    /** Indexing failed (see errorMessage for details) */
    FAILED,
}
