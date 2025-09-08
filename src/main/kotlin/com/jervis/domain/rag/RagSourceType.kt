package com.jervis.domain.rag

/**
 * Types of sources for documents.
 */
enum class RagSourceType {
    LLM,
    FILE,
    GIT,
    ANALYSIS,
    CLASS,
    METHOD,
    AGENT,
    DOCUMENTATION,
    URL,
    MEETING_TRANSCRIPT,
}
