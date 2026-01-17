package com.jervis.rpc

import com.jervis.dto.ChatRequestDto
import com.jervis.dto.ChatResponseDto
import com.jervis.mapper.toDomain
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.springframework.stereotype.Service

@Service
class AgentOrchestratorRpcImpl(
    private val agentOrchestratorService: AgentOrchestratorService,
) : IAgentOrchestratorService {
    override suspend fun handle(request: ChatRequestDto) {
        CoroutineScope(Dispatchers.Default).launch {
            agentOrchestratorService.handle(request.text, request.context.toDomain())
        }
    }

    override suspend fun chat(request: ChatRequestDto): ChatResponseDto {
        val response =
            agentOrchestratorService.handle(
                text = request.text,
                ctx = request.context.toDomain(),
            )
        return ChatResponseDto(message = response.message)
    }
}
