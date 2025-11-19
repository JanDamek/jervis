package com.jervis.domain.rag

/**
 * Severity level for knowledge rules.
 *
 * - MUST: Critical requirement, violation is unacceptable
 * - SHOULD: Strong recommendation, should be followed unless justified
 * - INFO: Informational guideline, optional
 */
enum class KnowledgeSeverity {
    /** Critical requirement - must be enforced */
    MUST,

    /** Strong recommendation - should be followed */
    SHOULD,

    /** Informational guideline - optional */
    INFO,
}
