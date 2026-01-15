package com.jervis.service

import com.jervis.dto.ChatRequestDto
import com.jervis.dto.ChatResponseDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IAgentOrchestratorService {
    suspend fun handle(request: ChatRequestDto)

    suspend fun chat(request: ChatRequestDto): ChatResponseDto
}
