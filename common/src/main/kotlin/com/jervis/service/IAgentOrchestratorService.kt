package com.jervis.service

import com.jervis.dto.ChatRequestContext
import com.jervis.dto.ChatResponse

interface IAgentOrchestratorService {
    suspend fun handle(
        text: String,
        ctx: ChatRequestContext,
    ): ChatResponse
}
