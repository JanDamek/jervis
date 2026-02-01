package com.jervis.service.learning

import com.jervis.entity.LearningDocument
import com.jervis.entity.LearningSource
import com.jervis.entity.LearningType
import com.jervis.repository.LearningRepository
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Service for managing agent learning with scope-based retrieval.
 *
 * Retrieval order (most specific to least specific):
 * 1. PROJECT scope (clientId + projectId)
 * 2. CLIENT scope (clientId only)
 * 3. GENERAL scope (no IDs)
 */
@Service
class LearningService(
    private val learningRepository: LearningRepository
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Store new learning (agent autonomous learning).
     *
     * IMPORTANT: Only store if confidence >= 0.5 to prevent noise!
     */
    suspend fun storeLearning(
        summary: String,
        category: String,
        learningType: LearningType,
        source: LearningSource,
        confidence: Double,
        clientId: ClientId? = null,
        projectId: ProjectId? = null,
        content: String? = null,
        evidence: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        sourceTaskId: String? = null
    ): LearningDocument? {
        // Reject low-confidence learnings
        if (confidence < 0.5) {
            logger.warn {
                "LEARNING_REJECTED | confidence=$confidence < 0.5 | summary='$summary'"
            }
            return null
        }

        val learning = LearningDocument(
            clientId = clientId,
            projectId = projectId,
            summary = summary,
            content = content,
            category = category,
            learningType = learningType,
            source = source,
            confidence = confidence,
            sourceTaskId = sourceTaskId,
            evidence = evidence,
            tags = tags,
            learnedAt = Instant.now()
        )

        val saved = learningRepository.save(learning)
        logger.info {
            "LEARNING_STORED | category=$category | type=$learningType | " +
            "confidence=$confidence | scope=${getScopeString(clientId, projectId)} | summary='$summary'"
        }
        return saved
    }

    /**
     * Get all learnings for a scope (for agent context loading).
     * Returns learnings in order: GENERAL → CLIENT → PROJECT.
     */
    suspend fun getAllLearnings(
        clientId: ClientId? = null,
        projectId: ProjectId? = null,
        minConfidence: Double = 0.5
    ): List<LearningDocument> {
        val learnings = mutableListOf<LearningDocument>()

        // 1. Global learnings
        learningRepository.findByClientIdIsNullAndProjectIdIsNullAndIsValidTrue()
            .toList()
            .filter { it.confidence >= minConfidence }
            .let { learnings.addAll(it) }

        // 2. Client learnings
        if (clientId != null) {
            learningRepository.findByClientIdAndProjectIdAndIsValidTrue(clientId, null)
                .toList()
                .filter { it.confidence >= minConfidence }
                .let { learnings.addAll(it) }
        }

        // 3. Project learnings
        if (clientId != null && projectId != null) {
            learningRepository.findByClientIdAndProjectIdAndIsValidTrue(clientId, projectId)
                .toList()
                .filter { it.confidence >= minConfidence }
                .let { learnings.addAll(it) }
        }

        logger.info {
            "LEARNINGS_LOADED | count=${learnings.size} | minConfidence=$minConfidence | " +
            "scope=${getScopeString(clientId, projectId)}"
        }

        return learnings
    }

    /**
     * Get learnings by category.
     */
    suspend fun getLearningsByCategory(
        category: String,
        minConfidence: Double = 0.5
    ): List<LearningDocument> {
        return learningRepository.findByCategoryAndIsValidTrue(category)
            .toList()
            .filter { it.confidence >= minConfidence }
    }

    /**
     * Confirm learning (increment success count, boost confidence).
     */
    suspend fun confirmLearning(id: org.bson.types.ObjectId): LearningDocument? {
        val learning = learningRepository.findById(id) ?: return null

        val updated = learning.copy(
            successCount = learning.successCount + 1,
            lastUsedAt = Instant.now(),
            // Boost confidence slightly (max 1.0)
            confidence = minOf(1.0, learning.confidence + 0.05)
        )

        val saved = learningRepository.save(updated)
        logger.info { "LEARNING_CONFIRMED | id=$id | newConfidence=${saved.confidence}" }
        return saved
    }

    /**
     * Contradict learning (increment failure count, reduce confidence or invalidate).
     */
    suspend fun contradictLearning(id: org.bson.types.ObjectId): LearningDocument? {
        val learning = learningRepository.findById(id) ?: return null

        val newConfidence = learning.confidence - 0.1
        val updated = learning.copy(
            failureCount = learning.failureCount + 1,
            confidence = maxOf(0.0, newConfidence),
            isValid = newConfidence >= 0.3  // Invalidate if confidence drops too low
        )

        val saved = learningRepository.save(updated)
        logger.warn {
            "LEARNING_CONTRADICTED | id=$id | newConfidence=${saved.confidence} | isValid=${saved.isValid}"
        }
        return saved
    }

    /**
     * Invalidate learning manually.
     */
    suspend fun invalidateLearning(id: org.bson.types.ObjectId): Boolean {
        val learning = learningRepository.findById(id) ?: return false
        learningRepository.save(learning.copy(isValid = false))
        logger.info { "LEARNING_INVALIDATED | id=$id" }
        return true
    }

    private fun getScopeString(clientId: ClientId?, projectId: ProjectId?): String {
        return when {
            clientId == null && projectId == null -> "GENERAL"
            projectId == null -> "CLIENT:$clientId"
            else -> "PROJECT:$clientId/$projectId"
        }
    }
}
