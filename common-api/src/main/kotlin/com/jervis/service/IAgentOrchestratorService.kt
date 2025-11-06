package com.jervis.service

import com.jervis.dto.ChatRequestDto
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

@HttpExchange("/api/agent")
fun interface IAgentOrchestratorService {
    @PostExchange("/handle")
    suspend fun handle(
        @RequestBody request: ChatRequestDto,
    )
}
