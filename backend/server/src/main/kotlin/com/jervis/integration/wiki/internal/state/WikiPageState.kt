package com.jervis.integration.wiki.internal.state

/**
 * Wiki page indexing states.
 * Mirrors EmailMessageState and BugTrackerIssueState.
 */
enum class WikiPageState {
    /** Fetched from API, ready to index */
    NEW,

    /** Currently being indexed */
    INDEXING,

    /** Successfully indexed to RAG */
    INDEXED,

    /** Failed to index (see error message in logs) */
    FAILED,
}
