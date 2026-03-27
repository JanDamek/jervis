package com.jervis.task

import com.jervis.dto.pipeline.ActionType
import com.jervis.dto.pipeline.EstimatedComplexity
import com.jervis.knowledgebase.model.FullIngestResult
import com.jervis.qualifier.ActionTypeInferrer
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Calculates priority score (0–100) for background tasks.
 *
 * Priority rules:
 * - Security vulnerabilities: 90+
 * - Approaching deadline (<3d): 80+
 * - User-created tasks: 70+
 * - Assigned to me: 65+
 * - Urgent from qualifier: 60+
 * - Auto-discovered (actionable): 50
 * - Low urgency / notifications: 20–30
 *
 * BackgroundEngine.getNextBackgroundTask() uses this score for ordering
 * instead of the previous FIFO (createdAt ASC) approach.
 */
@Component
class TaskPriorityCalculator {
    private val logger = KotlinLogging.logger {}

    /**
     * Calculate priority score from qualifier result and inferred fields.
     *
     * @param result KB qualification result
     * @param inferred Inferred action type and complexity
     * @param isUserCreated Whether the task was created by user (not from polling)
     * @return Priority score 0–100 with human-readable reason
     */
    fun calculate(
        result: FullIngestResult,
        inferred: ActionTypeInferrer.InferredFields,
        isUserCreated: Boolean = false,
        mentionsJervis: Boolean = false,
    ): PriorityResult {
        var score = 50 // Default for auto-discovered actionable content
        val reasons = mutableListOf<String>()

        // Base: user-created tasks get a boost
        if (isUserCreated) {
            score = maxOf(score, 70)
            reasons.add("user-created")
        }

        // Direct @mention of Jervis → high priority (above assigned-to-me)
        if (mentionsJervis) {
            score = maxOf(score, 80)
            reasons.add("mentioned-jervis")
        }

        // Urgency from qualifier
        when (result.urgency) {
            "urgent" -> {
                score = maxOf(score, 60)
                reasons.add("urgent")
            }
            "low" -> {
                score = minOf(score, 30)
                reasons.add("low-urgency")
            }
        }

        // Assigned to me → higher priority
        if (result.isAssignedToMe) {
            score = maxOf(score, 65)
            reasons.add("assigned-to-me")
        }

        // Deadline proximity
        result.suggestedDeadline?.let { deadlineStr ->
            try {
                val deadline = Instant.parse(deadlineStr)
                val daysUntil = Duration.between(Instant.now(), deadline).toDays()
                when {
                    daysUntil < 0 -> {
                        score = maxOf(score, 85)
                        reasons.add("overdue")
                    }
                    daysUntil <= 1 -> {
                        score = maxOf(score, 85)
                        reasons.add("deadline-tomorrow")
                    }
                    daysUntil <= 3 -> {
                        score = maxOf(score, 80)
                        reasons.add("deadline-3d")
                    }
                    daysUntil <= 7 -> {
                        score = maxOf(score, 60)
                        reasons.add("deadline-7d")
                    }
                }
            } catch (_: Exception) {
                // Ignore unparseable deadline
            }
        }

        // Security-related content → highest priority
        val securityKeywords = listOf("vulnerability", "cve", "security", "exploit", "injection", "xss")
        val summaryLower = result.summary.lowercase()
        if (securityKeywords.any { it in summaryLower }) {
            score = maxOf(score, 90)
            reasons.add("security")
        }

        // Action type adjustments
        when (inferred.actionType) {
            ActionType.CODE_FIX -> {
                score = maxOf(score, 55)
                reasons.add("code-fix")
            }
            ActionType.RESPOND_EMAIL -> {
                score = maxOf(score, 50)
                reasons.add("email-response")
            }
            ActionType.NOTIFY_ONLY -> {
                score = minOf(score, 25)
                reasons.add("notification-only")
            }
            else -> {}
        }

        // Complexity adjustments (complex tasks slightly lower to avoid blocking simple wins)
        when (inferred.estimatedComplexity) {
            EstimatedComplexity.TRIVIAL -> score = minOf(score + 5, 100)
            EstimatedComplexity.SIMPLE -> {} // no change
            EstimatedComplexity.MEDIUM -> score = maxOf(score - 3, 0)
            EstimatedComplexity.COMPLEX -> score = maxOf(score - 5, 0)
        }

        val reason = reasons.joinToString(", ")
        logger.debug { "PRIORITY_CALC: score=$score reasons=$reason" }

        return PriorityResult(score.coerceIn(0, 100), reason)
    }

    data class PriorityResult(
        val score: Int,
        val reason: String,
    )
}
