package com.jervis.koog.tools.preferences

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.PreferenceSource
import com.jervis.entity.TaskDocument
import com.jervis.service.preferences.PreferenceService
import kotlinx.serialization.Serializable
import mu.KotlinLogging

/**
 * Agent preference tools - allow agents to read and modify their own preferences.
 *
 * Preferences are scoped (GLOBAL / CLIENT / PROJECT) and self-modifiable.
 * Agents can learn from successful patterns and store preferences.
 */
@LLMDescription("Read and modify agent preferences for personalization and learning")
class PreferenceTools(
    private val task: TaskDocument,
    private val preferenceService: PreferenceService
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription(
        """Get agent preference value with automatic scope fallback.

        Lookup order: PROJECT → CLIENT → GLOBAL → default

        Common preference keys:
        - preferredLanguage: "cs", "en"
        - detailLevel: "concise", "detailed", "technical"
        - codingStyle: "functional", "object-oriented"
        - testStrategy: "unit-first", "integration-first"
        - errorHandling: "verbose", "minimal"
        - maxIterations: "5", "10", "20"

        Use this to understand user/project preferences before making decisions."""
    )
    suspend fun getPreference(
        @LLMDescription("Preference key (e.g., 'preferredLanguage', 'detailLevel')")
        key: String,
        @LLMDescription("Default value if preference not found")
        default: String? = null
    ): PreferenceResult {
        val value = preferenceService.getPreference(
            key = key,
            clientId = task.clientId,
            projectId = task.projectId,
            default = default
        )

        logger.info { "PREFERENCE_GET | key=$key | value=$value | taskId=${task.id}" }

        return PreferenceResult(
            key = key,
            value = value,
            found = value != null,
            scope = if (value != null) "AUTO" else "NONE"
        )
    }

    @Tool
    @LLMDescription(
        """Set or update agent preference (self-modification).

        Use this when you learn a successful pattern or user preference.

        Scope selection:
        - Use PROJECT scope for project-specific preferences (default)
        - Use CLIENT scope for user-wide preferences (set projectScope=false)
        - GLOBAL scope not writable by agents (admin only)

        Examples:
        - After user corrects: setPreference("detailLevel", "technical", confidence=0.9)
        - After successful pattern: setPreference("testStrategy", "unit-first", confidence=0.7)

        IMPORTANT: Only set preferences when you have clear evidence!"""
    )
    suspend fun setPreference(
        @LLMDescription("Preference key")
        key: String,
        @LLMDescription("Preference value")
        value: String,
        @LLMDescription("Confidence level 0.0-1.0 (0.5=tentative, 0.7=probable, 0.9=strong evidence)")
        confidence: Double = 0.7,
        @LLMDescription("Optional description why this preference was set")
        reason: String? = null,
        @LLMDescription("True=project scope (default), False=client scope")
        projectScope: Boolean = true
    ): PreferenceResult {
        // Validate confidence
        if (confidence < 0.0 || confidence > 1.0) {
            return PreferenceResult(
                key = key,
                value = null,
                found = false,
                scope = "ERROR",
                error = "Confidence must be between 0.0 and 1.0"
            )
        }

        // Don't allow low-confidence preferences (prevent noise)
        if (confidence < 0.5) {
            return PreferenceResult(
                key = key,
                value = null,
                found = false,
                scope = "REJECTED",
                error = "Confidence too low (min 0.5 required). Don't guess preferences!"
            )
        }

        val (clientId, projectId) = if (projectScope) {
            task.clientId to task.projectId
        } else {
            task.clientId to null
        }

        val saved = preferenceService.setPreference(
            key = key,
            value = value,
            clientId = clientId,
            projectId = projectId,
            source = PreferenceSource.AGENT_LEARNED,
            confidence = confidence,
            description = reason
        )

        logger.info {
            "PREFERENCE_SET | key=$key | value=$value | confidence=$confidence | " +
            "scope=${if (projectScope) "PROJECT" else "CLIENT"} | taskId=${task.id}"
        }

        return PreferenceResult(
            key = key,
            value = saved.value,
            found = true,
            scope = if (projectScope) "PROJECT" else "CLIENT"
        )
    }

    @Tool
    @LLMDescription(
        """Get all current preferences as a map (for context loading).

        Returns all preferences in scope hierarchy (GLOBAL → CLIENT → PROJECT).
        Use this at task start to understand user/project preferences."""
    )
    suspend fun getAllPreferences(): Map<String, String> {
        val prefs = preferenceService.getAllPreferences(
            clientId = task.clientId,
            projectId = task.projectId
        )

        logger.info { "PREFERENCES_LOADED | count=${prefs.size} | taskId=${task.id}" }

        return prefs
    }

    @Serializable
    data class PreferenceResult(
        val key: String,
        val value: String?,
        val found: Boolean,
        val scope: String,
        val error: String? = null
    )
}
