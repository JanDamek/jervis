package com.jervis.koog.qualifier.goap

import com.jervis.koog.vision.AttachmentDescription

/**
 * GOAP State Model for Qualifier Agent.
 *
 * Fluents:
 * - contentAnalyzed: Content prepared for indexing
 * - allIndexed: All content indexed to RAG + Graph
 * - taskRouted: Routing decision made via tool
 *
 * GOAP Planner uses these fluents to plan action sequence.
 */
data class QualifierGoapState(
    val contentAnalyzed: Boolean = false,
    val allIndexed: Boolean = false,
    val taskRouted: Boolean = false,

    val contentToProcess: String = "",
    val visionDescriptions: List<AttachmentDescription> = emptyList(),
) {
    companion object {
        fun initial(visionDescriptions: List<AttachmentDescription> = emptyList()): QualifierGoapState =
            QualifierGoapState(
                visionDescriptions = visionDescriptions,
            )
    }
}
