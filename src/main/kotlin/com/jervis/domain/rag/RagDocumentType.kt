package com.jervis.domain.rag

/**
 * Types of documents.
 */
enum class RagDocumentType {
    UNKNOWN,
    CODE,
    TEXT,
    MEETING,
    NOTE,
    GIT_HISTORY,
    DEPENDENCY,
    DEPENDENCY_DESCRIPTION,
    CLASS_SUMMARY,
    METHOD_DESCRIPTION,
    ACTION,
    DECISION,
    PLAN,
    JOERN_ANALYSIS,
    DOCUMENTATION,
}
