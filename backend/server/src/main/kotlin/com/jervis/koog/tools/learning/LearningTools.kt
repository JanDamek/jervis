package com.jervis.koog.tools.learning

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.LearningSource
import com.jervis.entity.LearningType
import com.jervis.entity.TaskDocument
import com.jervis.service.learning.LearningService
import kotlinx.serialization.Serializable
import mu.KotlinLogging

/**
 * Agent learning tools - allow agents to store and retrieve learned knowledge.
 *
 * Agents can autonomously:
 * 1. Store successful patterns and discoveries
 * 2. Retrieve relevant past learnings
 * 3. Confirm or contradict existing learnings
 */
@LLMDescription("Store and retrieve learned knowledge for autonomous improvement")
class LearningTools(
    private val task: TaskDocument,
    private val learningService: LearningService
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription(
        """Store new learning from successful task completion or discovery.

        Use this when you:
        - Discover a successful solution pattern
        - Learn a project-specific fact
        - Identify a constraint or requirement
        - Infer a user preference

        Learning scopes:
        - projectScope=true: Project-specific (default)
        - projectScope=false: Client-wide
        - GENERAL scope not writable by agents

        Learning types:
        - PATTERN: Solution pattern (e.g., "For X, use Y")
        - FACT: Verified fact (e.g., "Database is PostgreSQL")
        - CONSTRAINT: Requirement (e.g., "Never use library X")
        - PREFERENCE: User preference (e.g., "Prefers detailed explanations")
        - TECHNIQUE: Proven workflow (e.g., "Use TDD for critical code")

        IMPORTANT: Only store if confidence >= 0.5!
        Examples:
        - Discovered solution: confidence=0.7
        - User confirmed: confidence=0.9
        - Verified in docs: confidence=1.0"""
    )
    suspend fun storeLearning(
        @LLMDescription("What was learned (concise summary)")
        summary: String,
        @LLMDescription("Category: architecture, database, api, language, testing, deployment, etc.")
        category: String,
        @LLMDescription("Type: PATTERN, FACT, CONSTRAINT, PREFERENCE, TECHNIQUE")
        learningType: String,
        @LLMDescription("Confidence 0.5-1.0 (0.5=single observation, 0.7=strong evidence, 1.0=verified)")
        confidence: Double,
        @LLMDescription("Optional detailed content")
        content: String? = null,
        @LLMDescription("Optional evidence (file paths, URLs, etc.)")
        evidence: List<String> = emptyList(),
        @LLMDescription("Optional tags for categorization")
        tags: List<String> = emptyList(),
        @LLMDescription("True=project scope (default), False=client scope")
        projectScope: Boolean = true
    ): LearningResult {
        // Validate confidence
        if (confidence < 0.5 || confidence > 1.0) {
            return LearningResult(
                success = false,
                message = "Confidence must be between 0.5 and 1.0 (minimum 0.5 required!)",
                learningId = null
            )
        }

        // Parse learning type
        val type = try {
            LearningType.valueOf(learningType.uppercase())
        } catch (e: IllegalArgumentException) {
            return LearningResult(
                success = false,
                message = "Invalid learningType. Must be: PATTERN, FACT, CONSTRAINT, PREFERENCE, TECHNIQUE",
                learningId = null
            )
        }

        val (clientId, projectId) = if (projectScope) {
            task.clientId to task.projectId
        } else {
            task.clientId to null
        }

        val stored = learningService.storeLearning(
            summary = summary,
            category = category,
            learningType = type,
            source = LearningSource.TASK_SUCCESS,
            confidence = confidence,
            clientId = clientId,
            projectId = projectId,
            content = content,
            evidence = evidence,
            tags = tags,
            sourceTaskId = task.id.toString()
        )

        if (stored == null) {
            return LearningResult(
                success = false,
                message = "Learning rejected (confidence < 0.5 or duplicate)",
                learningId = null
            )
        }

        logger.info {
            "LEARNING_STORED_BY_AGENT | taskId=${task.id} | category=$category | " +
            "type=$learningType | confidence=$confidence"
        }

        return LearningResult(
            success = true,
            message = "Learning stored successfully",
            learningId = stored.id.toString()
        )
    }

    @Tool
    @LLMDescription(
        """Retrieve all learnings relevant to current task context.

        Returns learnings from all applicable scopes (GENERAL + CLIENT + PROJECT).
        Use this at task start to benefit from past learnings.

        Optional filters:
        - category: Filter by category (e.g., "database", "api")
        - minConfidence: Minimum confidence threshold (default 0.5)"""
    )
    suspend fun retrieveLearnings(
        @LLMDescription("Optional category filter")
        category: String? = null,
        @LLMDescription("Minimum confidence threshold (0.5-1.0)")
        minConfidence: Double = 0.5
    ): List<LearningSummary> {
        val learnings = if (category != null) {
            learningService.getLearningsByCategory(category, minConfidence)
        } else {
            learningService.getAllLearnings(
                clientId = task.clientId,
                projectId = task.projectId,
                minConfidence = minConfidence
            )
        }

        logger.info {
            "LEARNINGS_RETRIEVED_BY_AGENT | taskId=${task.id} | count=${learnings.size} | " +
            "category=$category | minConfidence=$minConfidence"
        }

        return learnings.map {
            LearningSummary(
                id = it.id.toString(),
                summary = it.summary,
                category = it.category,
                learningType = it.learningType.name,
                confidence = it.confidence,
                scope = when {
                    it.clientId == null -> "GENERAL"
                    it.projectId == null -> "CLIENT"
                    else -> "PROJECT"
                }
            )
        }
    }

    @Serializable
    data class LearningResult(
        val success: Boolean,
        val message: String,
        val learningId: String?
    )

    @Serializable
    data class LearningSummary(
        val id: String,
        val summary: String,
        val category: String,
        val learningType: String,
        val confidence: Double,
        val scope: String
    )
}
