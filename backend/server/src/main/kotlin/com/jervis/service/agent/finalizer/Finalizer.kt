package com.jervis.service.agent.finalizer

import com.jervis.domain.plan.Plan
import com.jervis.dto.ChatResponse
import org.springframework.stereotype.Service

/**
 * Simplified Finalizer that converts Plan's final answer into ChatResponse.
 * No translation needed - Koog agent already handles language in its system prompt.
 */
@Service
class Finalizer {
    fun finalize(plan: Plan): ChatResponse {
        val message = plan.finalAnswer ?: "No response generated"
        return ChatResponse(message = message)
    }
}
