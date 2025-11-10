package com.jervis.controller.api

import com.jervis.dto.ChatRequestDto
import com.jervis.mapper.toDomain
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/agent")
class AgentOrchestratorRestController(
    private val agentOrchestratorService: AgentOrchestratorService,
) : IAgentOrchestratorService {
    @PostMapping("/handle")
    @ResponseStatus(HttpStatus.ACCEPTED)
    override suspend fun handle(@RequestBody request: ChatRequestDto) {
        CoroutineScope(Dispatchers.Default).launch {
            agentOrchestratorService.handle(request.text, request.context.toDomain(), background = false)
        }
    }
}
