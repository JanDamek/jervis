package com.jervis.service

import com.jervis.dto.AgentQuestionDto
import kotlinx.rpc.annotations.Rpc

/**
 * IAgentQuestionService — RPC interface for agent-to-user questions.
 *
 * Agents submit questions when they need user input during task execution.
 * Users see pending questions in the UI and provide answers.
 */
@Rpc
interface IAgentQuestionService {
    /** Submit a new question from an agent. Returns the question document ID. */
    suspend fun submitQuestion(
        taskId: String,
        question: String,
        context: String = "",
        priority: String = "QUESTION",
        clientId: String? = null,
        projectId: String? = null,
    ): String

    /** Answer a pending question. Returns true if successfully answered. */
    suspend fun answerQuestion(questionId: String, answer: String): Boolean

    /** Get pending/presented questions, optionally filtered by client. */
    suspend fun getPendingQuestions(clientId: String? = null): List<AgentQuestionDto>

    /** Get count of pending/presented questions for badge display. */
    suspend fun getPendingCount(clientId: String? = null): Int
}
