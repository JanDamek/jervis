package com.jervis.service

import com.jervis.dto.ChatRequest

fun interface IAgentOrchestratorService {
    fun handle(request: ChatRequest)
}
