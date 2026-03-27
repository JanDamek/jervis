package com.jervis.dto.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentQuestionDto(
    val id: String,
    val taskId: String,
    val agentType: String,
    val question: String,
    val context: String,
    val priority: String,
    val clientId: String?,
    val projectId: String?,
    val state: String,
    val createdAt: String,
)
