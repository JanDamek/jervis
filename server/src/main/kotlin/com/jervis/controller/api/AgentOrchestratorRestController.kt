package com.jervis.controller.api

import com.jervis.dto.ChatRequestDto
import com.jervis.mapper.toDomain
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class AgentOrchestratorRestController(
    private val agentOrchestratorService: AgentOrchestratorService,
) : IAgentOrchestratorService {
    @ResponseStatus(HttpStatus.ACCEPTED)
    override suspend fun handle(request: ChatRequestDto) {
        CoroutineScope(Dispatchers.Default).launch {
            agentOrchestratorService.handle(request.text, request.context.toDomain(), background = false)
        }
    }
}
