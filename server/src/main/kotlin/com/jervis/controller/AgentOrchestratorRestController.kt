package com.jervis.controller

import com.jervis.dto.ChatRequest
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
) {
    @PostMapping("/handle")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun handle(
        @RequestBody request: ChatRequest,
    ) {
        // Fire-and-forget: start agent processing in background and return 202 Accepted immediately
        // Results will be sent via WebSocket notifications
        CoroutineScope(Dispatchers.Default).launch {
            agentOrchestratorService.handle(request.text, request.context)
        }
    }
}
