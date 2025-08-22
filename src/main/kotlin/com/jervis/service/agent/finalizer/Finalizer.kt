package com.jervis.service.agent.finalizer

import com.jervis.dto.ChatResponse
import com.jervis.service.agent.planner.PlannerResult

/**
 * Finalizer produces the final user-facing response based on accumulated context and planner outcome.
 */
fun interface Finalizer {
    suspend fun finalize(
        plannerResult: PlannerResult,
        requestLanguage: String,
        englishText: String?,
    ): ChatResponse
}
