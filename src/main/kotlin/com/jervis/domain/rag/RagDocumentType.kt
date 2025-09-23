package com.jervis.domain.rag

/**
 * Types of documents.
 */
enum class RagDocumentType {
    UNKNOWN,
    CODE,
    TEXT,
    MEETING,
    GIT_HISTORY,
    DEPENDENCY,
    DEPENDENCY_DESCRIPTION,
    CLASS_SUMMARY,
    METHOD_DESCRIPTION,
    JOERN_ANALYSIS,
    DOCUMENTATION,
}
