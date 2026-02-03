package com.jervis.domain.rag

/**
 * Source type for documents/links indexed into RAG.
 * Minimal set covers current usages across the server.
 */
enum class RagSourceType {
    // Generic
    URL,

    // Email-related
    EMAIL,
    EMAIL_LINK_CONTENT,

    // Wiki integration
    WIKI_LINK_CONTENT,

    // Web documentation
    DOCUMENTATION,
    BUGTRACKER_LINK_CONTENT,
}
