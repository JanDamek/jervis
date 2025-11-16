package com.jervis.service

import com.jervis.dto.ChatRequestDto
import com.jervis.dto.ChatResponseDto
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.POST

interface IAgentOrchestratorService {
    @POST("api/agent/handle")
    suspend fun handle(
        @Body request: ChatRequestDto,
    )

    @POST("api/agent/chat")
    suspend fun chat(
        @Body request: ChatRequestDto,
    ): ChatResponseDto
}
