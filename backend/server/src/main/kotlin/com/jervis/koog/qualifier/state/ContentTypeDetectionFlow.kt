package com.jervis.koog.qualifier.state

import com.jervis.koog.qualifier.types.ContentType
import com.jervis.koog.qualifier.types.ContentTypeDetection

/**
 * Wrapper for deterministic dataflow through content type detection.
 *
 * This type carries QualifierPipelineState through LLM structured nodes
 * WITHOUT using mutable vars (neither in strategy nor in subgraph).
 *
 * **Koog best practice:** State flows exclusively through node input/output types.
 * Reference: Koog docs → Custom strategy graphs → Deterministic dataflow
 *
 * @property state Pipeline state (NEVER lost, NEVER mutable)
 * @property prompt Prepared prompt for LLM
 */
data class DetectionWorkItem(
    val state: QualifierPipelineState,
    val prompt: String,
) {
    /**
     * Combine with detection result to produce final state.
     *
     * This method is called after LLM returns detection result.
     * It's a pure function - no side effects, no mutable state.
     */
    fun withDetectionResult(
        detection: ContentTypeDetection?,
        error: String? = null,
    ): QualifierPipelineState {
        if (detection == null) {
            return state
                .withContentType(detection = null, contentType = ContentType.GENERIC)
                .withError(error ?: "Content type detection failed - no result")
        }

        val contentType =
            when (detection.contentType.uppercase()) {
                "EMAIL" -> ContentType.EMAIL
                "JIRA", "BUGTRACKER_ISSUE", "BUGTRACKER", "ISSUE" -> ContentType.BUGTRACKER_ISSUE
                "CONFLUENCE", "WIKI_PAGE", "WIKI" -> ContentType.WIKI_PAGE
                "LOG" -> ContentType.LOG
                else -> ContentType.GENERIC
            }

        return state.withContentType(detection, contentType)
    }
}

/**
 * Result after detection - carries both work item and LLM result.
 *
 * This allows deterministic merge without mutable state.
 */
data class DetectionResultItem(
    val workItem: DetectionWorkItem,
    val detection: ContentTypeDetection?,
    val error: String? = null,
) {
    /**
     * Finalize to pipeline state.
     */
    fun toState(): QualifierPipelineState = workItem.withDetectionResult(detection, error)
}
