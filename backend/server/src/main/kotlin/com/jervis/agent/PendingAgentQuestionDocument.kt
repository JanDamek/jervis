package com.jervis.agent

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * PendingAgentQuestionDocument — question from an agent that needs user input.
 *
 * When a coding/review agent encounters ambiguity or needs a decision,
 * it creates a PendingAgentQuestion. The user answers via UI,
 * and the agent picks up the answer to continue.
 */
@Document(collection = "pending_agent_questions")
data class PendingAgentQuestionDocument(
    @Id val id: ObjectId = ObjectId(),
    @Indexed val taskId: String,
    val agentType: String = "coding",  // coding, review, etc.
    val question: String,
    val context: String = "",
    val priority: QuestionPriority = QuestionPriority.QUESTION,
    @Indexed val clientId: String? = null,
    val projectId: String? = null,
    val answer: String? = null,
    @Indexed val state: QuestionState = QuestionState.PENDING,
    val createdAt: Instant = Instant.now(),
    val answeredAt: Instant? = null,
    val presentedAt: Instant? = null,
    val expiresAt: Instant? = null,  // auto-expire after timeout
)

enum class QuestionPriority { BLOCKING, QUESTION, INFO }
enum class QuestionState { PENDING, PRESENTED, ANSWERED, EXPIRED }
