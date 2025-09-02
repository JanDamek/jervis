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
    ACTION,
    DECISION,
    PLAN,
    JOERN_ANALYSIS,
}
