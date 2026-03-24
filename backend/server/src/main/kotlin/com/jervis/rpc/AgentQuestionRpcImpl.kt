package com.jervis.rpc

import com.jervis.dto.AgentQuestionDto
import com.jervis.entity.PendingAgentQuestionDocument
import com.jervis.entity.QuestionPriority
import com.jervis.service.IAgentQuestionService
import com.jervis.service.agent.AgentQuestionService
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class AgentQuestionRpcImpl(
    private val agentQuestionService: AgentQuestionService,
) : IAgentQuestionService {
    private val logger = KotlinLogging.logger {}

    override suspend fun submitQuestion(
        taskId: String,
        question: String,
        context: String,
        priority: String,
        clientId: String?,
        projectId: String?,
    ): String {
        val questionPriority = try {
            QuestionPriority.valueOf(priority)
        } catch (_: IllegalArgumentException) {
            QuestionPriority.QUESTION
        }
        return agentQuestionService.submitQuestion(
            taskId = taskId,
            question = question,
            context = context,
            priority = questionPriority,
            clientId = clientId,
            projectId = projectId,
        )
    }

    override suspend fun answerQuestion(questionId: String, answer: String): Boolean {
        return agentQuestionService.answerQuestion(questionId, answer)
    }

    override suspend fun getPendingQuestions(clientId: String?): List<AgentQuestionDto> {
        return agentQuestionService.getPendingQuestions(clientId).map { it.toDto() }
    }

    override suspend fun getPendingCount(clientId: String?): Int {
        return agentQuestionService.getPendingCount(clientId).toInt()
    }
}

private fun PendingAgentQuestionDocument.toDto(): AgentQuestionDto = AgentQuestionDto(
    id = id.toString(),
    taskId = taskId,
    agentType = agentType,
    question = question,
    context = context,
    priority = priority.name,
    clientId = clientId,
    projectId = projectId,
    state = state.name,
    createdAt = createdAt.toString(),
)
