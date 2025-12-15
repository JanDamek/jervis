package com.jervis.controller.api

import com.jervis.dto.ChatRequestDto
import com.jervis.dto.ChatResponseDto
import com.jervis.mapper.toDomain
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/agent")
class AgentOrchestratorRestController(
    private val agentOrchestratorService: AgentOrchestratorService,
) : IAgentOrchestratorService {
    @PostMapping("/handle")
    @ResponseStatus(HttpStatus.ACCEPTED)
    override suspend fun handle(
        @RequestBody request: ChatRequestDto,
    ) {
        CoroutineScope(Dispatchers.Default).launch {
            agentOrchestratorService.handle(request.text, request.context.toDomain())
        }
    }

    /**
     * Synchronous chat endpoint that returns the final agent answer for Main Window chat UX.
     */
    @PostMapping("/chat")
    override suspend fun chat(
        @RequestBody request: ChatRequestDto,
    ): ChatResponseDto {
        val response =
            agentOrchestratorService.handle(
                text = request.text,
                ctx = request.context.toDomain(),
            )
        return ChatResponseDto(message = response.message)
    }
}
