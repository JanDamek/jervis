package com.jervis.service

import com.jervis.dto.ChatRequestDto
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.POST

interface IAgentOrchestratorService {
    @POST("api/agent/handle")
    suspend fun handle(
        @Body request: ChatRequestDto,
    )
}
