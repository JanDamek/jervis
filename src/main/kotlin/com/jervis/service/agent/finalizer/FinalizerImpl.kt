package com.jervis.service.agent.finalizer

import com.jervis.dto.ChatResponse
import com.jervis.service.agent.coordinator.LanguageOrchestrator
import com.jervis.service.agent.planner.PlannerResult
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Temporary finalizer implementation that simply returns PlannerResult fields.
 * If needed, it can translate message to the request language.
 */
@Service
class FinalizerImpl(
    private val language: LanguageOrchestrator,
) : Finalizer {
    private val logger = KotlinLogging.logger {}

    override suspend fun finalize(
        plannerResult: PlannerResult,
        requestLanguage: String,
        englishText: String?,
    ): ChatResponse {
        val msg = plannerResult.message
        // Optionally translate message to request language (already in correct lang usually)
        // For now, we keep the message as-is to avoid unnecessary translation cycles.
        return ChatResponse(
            message = msg,
            detectedClient = plannerResult.detectedClient,
            detectedProject = plannerResult.detectedProject,
            englishText = englishText ?: plannerResult.englishText,
        )
    }
}
