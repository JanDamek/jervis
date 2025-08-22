package com.jervis.service.agent.coordinator

import com.jervis.dto.ChatRequestContext
import com.jervis.dto.ChatResponse
import org.springframework.stereotype.Service

/**
 * ChatCoordinatorService is a thin façade that exposes the chat handling API
 * expected by UI and controllers while delegating actual orchestration to
 * ChatCoordinator. This preserves a stable type/name for dependency injection.
 */
@Service
class ChatCoordinatorService(
    private val coordinator: ChatCoordinator,
) {
    /**
     * Handle a chat request.
     *
     * @param text The user message (any language)
     * @param ctx  The request context (client/project scope hints)
     * @return A response prepared for display to the user
     */
    suspend fun handle(
        text: String,
        ctx: ChatRequestContext,
    ): ChatResponse = coordinator.handle(text, ctx)
}
