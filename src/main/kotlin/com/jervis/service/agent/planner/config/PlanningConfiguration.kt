package com.jervis.service.agent.planner.config

/**
 * Configuration constants for the planning system.
 * Centralizes magic numbers and default values for better maintainability.
 */
object PlanningConfiguration {
    /**
     * Discovery phase configuration
     */
    object Discovery {
        /** Maximum number of RAG hits to process from knowledge search */
        const val MAX_RAG_HITS = 15

        /** Maximum content length for each RAG hit */
        const val MAX_HIT_CONTENT_LENGTH = 500

        /** Default confidence score for knowledge search results */
        const val DEFAULT_KNOWLEDGE_SCORE = 0.8f

        /** Maximum number of text hits to include in discovery context */
        const val MAX_TEXT_HITS_FOR_CONTEXT = 3

        /** Maximum number of code hits to include in discovery context */
        const val MAX_CODE_HITS_FOR_CONTEXT = 3

        /** Maximum length of content preview in context */
        const val CONTEXT_PREVIEW_LENGTH = 100
    }

    /**
     * Goal creation configuration
     */
    object Goals {
        /** Minimum ratio of goals to checklist items (should be at least 1:1) */
        const val MIN_GOAL_TO_CHECKLIST_RATIO = 1.0
    }

    /**
     * Step expansion configuration
     */
    object Steps {
        /** Maximum number of tools to include in tool descriptions */
        const val MAX_TOOLS_IN_DESCRIPTION = 20

        /** Maximum length of content preview in step context */
        const val STEP_CONTEXT_PREVIEW_LENGTH = 150

        /** Maximum number of fallback steps to create per goal */
        const val MAX_FALLBACK_STEPS_PER_GOAL = 3
    }

    /**
     * Prompt context configuration
     */
    object Prompts {
        /** Common prompt placeholders */
        object Placeholders {
            const val CLIENT_DESCRIPTION = "clientDescription"
            const val PROJECT_DESCRIPTION = "projectDescription"
            const val PREVIOUS_CONVERSATIONS = "previousConversations"
            const val PLAN_HISTORY = "planHistory"
            const val PLAN_CONTEXT = "planContext"
            const val USER_REQUEST = "userRequest"
            const val QUESTION_CHECKLIST = "questionChecklist"
            const val INVESTIGATION_GUIDANCE = "investigationGuidance"
            const val AVAILABLE_TOOLS = "availableTools"
            const val TOOL_DESCRIPTIONS = "toolDescriptions"
        }
    }

    /**
     * Error handling configuration
     */
    object ErrorHandling {
        /** Whether to use fallback mechanisms when LLM calls fail */
        const val ENABLE_FALLBACKS = true

        /** Maximum retry attempts for failed operations */
        const val MAX_RETRY_ATTEMPTS = 2
    }

    /**
     * Tool filtering configuration
     */
    object Tools {
        /** Tool name prefixes to exclude from step planning */
        val EXCLUDED_TOOL_PREFIXES = setOf("PLANNING_", "RAG_")

        /** Specific tool names to exclude from step planning */
        val EXCLUDED_TOOL_NAMES = setOf("ANALYSIS_REASONING")

        /** Default fallback tools for different goal types */
        val FALLBACK_TOOLS =
            mapOf(
                "language" to "DOCUMENT_EXTRACT_TEXT",
                "auth" to "CODE_SEARCH_SYMBOL",
                "structure" to "PROJECT_EXPLORE_STRUCTURE",
            )
    }
}
