package com.jervis.service.agent

import com.jervis.entity.PendingAgentQuestionDocument
import com.jervis.entity.QuestionPriority
import com.jervis.entity.QuestionState
import com.jervis.repository.PendingAgentQuestionRepository
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * AgentQuestionService — manages agent-to-user questions.
 *
 * When a coding/review agent hits ambiguity, it submits a question via this service.
 * The user sees the question in the UI, answers it, and the agent picks up the answer.
 */
@Service
class AgentQuestionService(
    private val repository: PendingAgentQuestionRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Submit a new question from an agent.
     * @return the created document's ID as string
     */
    suspend fun submitQuestion(
        taskId: String,
        question: String,
        context: String = "",
        priority: QuestionPriority = QuestionPriority.QUESTION,
        clientId: String? = null,
        projectId: String? = null,
    ): String {
        val doc = PendingAgentQuestionDocument(
            taskId = taskId,
            question = question,
            context = context,
            priority = priority,
            clientId = clientId,
            projectId = projectId,
            expiresAt = Instant.now().plusSeconds(24 * 60 * 60), // 24h default TTL
        )
        val saved = repository.save(doc)
        logger.info { "AGENT_QUESTION_SUBMITTED | id=${saved.id} | taskId=$taskId | priority=$priority" }
        return saved.id.toString()
    }

    /**
     * Answer a pending question.
     */
    suspend fun answerQuestion(questionId: String, answer: String): Boolean {
        val doc = repository.findById(ObjectId(questionId)) ?: run {
            logger.warn { "AGENT_QUESTION_NOT_FOUND | id=$questionId" }
            return false
        }
        if (doc.state == QuestionState.ANSWERED || doc.state == QuestionState.EXPIRED) {
            logger.warn { "AGENT_QUESTION_ALREADY_RESOLVED | id=$questionId | state=${doc.state}" }
            return false
        }
        val updated = doc.copy(
            answer = answer,
            state = QuestionState.ANSWERED,
            answeredAt = Instant.now(),
        )
        repository.save(updated)
        logger.info { "AGENT_QUESTION_ANSWERED | id=$questionId | taskId=${doc.taskId}" }
        return true
    }

    /**
     * Mark a question as presented to the user.
     */
    suspend fun presentQuestion(questionId: String): Boolean {
        val doc = repository.findById(ObjectId(questionId)) ?: return false
        if (doc.state != QuestionState.PENDING) return false
        val updated = doc.copy(
            state = QuestionState.PRESENTED,
            presentedAt = Instant.now(),
        )
        repository.save(updated)
        logger.debug { "AGENT_QUESTION_PRESENTED | id=$questionId" }
        return true
    }

    /**
     * Get pending and presented questions, optionally filtered by client.
     */
    suspend fun getPendingQuestions(clientId: String? = null): List<PendingAgentQuestionDocument> {
        val activeStates = listOf(QuestionState.PENDING, QuestionState.PRESENTED)
        return if (clientId != null) {
            repository.findByClientIdAndStateIn(clientId, activeStates).toList()
        } else {
            repository.findByStateIn(activeStates).toList()
        }
    }

    /**
     * Get count of pending/presented questions for badge display.
     */
    suspend fun getPendingCount(clientId: String? = null): Long {
        val activeStates = listOf(QuestionState.PENDING, QuestionState.PRESENTED)
        return if (clientId != null) {
            repository.countByClientIdAndStateIn(clientId, activeStates)
        } else {
            repository.countByState(QuestionState.PENDING) + repository.countByState(QuestionState.PRESENTED)
        }
    }

    /**
     * Expire questions older than 24h that are still PENDING or PRESENTED.
     * Called periodically by a scheduled task.
     */
    suspend fun expireOldQuestions(): Int {
        val now = Instant.now()
        val activeStates = listOf(QuestionState.PENDING, QuestionState.PRESENTED)
        val candidates = repository.findByStateIn(activeStates).toList()
        var expiredCount = 0
        for (doc in candidates) {
            if (doc.expiresAt != null && doc.expiresAt.isBefore(now)) {
                repository.save(doc.copy(state = QuestionState.EXPIRED))
                expiredCount++
            } else if (doc.createdAt.plusSeconds(24 * 60 * 60).isBefore(now)) {
                // Fallback: expire if createdAt is older than 24h even without expiresAt
                repository.save(doc.copy(state = QuestionState.EXPIRED))
                expiredCount++
            }
        }
        if (expiredCount > 0) {
            logger.info { "AGENT_QUESTIONS_EXPIRED | count=$expiredCount" }
        }
        return expiredCount
    }
}
