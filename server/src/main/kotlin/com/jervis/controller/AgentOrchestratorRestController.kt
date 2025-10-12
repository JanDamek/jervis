package com.jervis.controller

import com.jervis.dto.ChatRequestContext
import com.jervis.dto.ChatResponse
import com.jervis.service.IAgentOrchestratorService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/agent")
class AgentOrchestratorRestController(
    private val agentOrchestratorService: IAgentOrchestratorService,
) {
    @PostMapping("/handle")
    suspend fun handle(
        @RequestBody request: ChatRequest,
    ): ChatResponse = agentOrchestratorService.handle(request.text, request.context)

    data class ChatRequest(
        val text: String,
        val context: ChatRequestContext,
    )
}
