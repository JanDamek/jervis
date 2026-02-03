package com.jervis.integration.bugtracker.internal.state

/**
 * Indexing state for BugTracker issues.
 */
enum class BugTrackerIssueState {
    /** Issue discovered from BugTracker API, not yet indexed */
    NEW,

    /** Currently being indexed (prevents concurrent processing) */
    INDEXING,

    /** Successfully indexed to RAG */
    INDEXED,

    /** Indexing failed (see errorMessage for details) */
    FAILED,
}
