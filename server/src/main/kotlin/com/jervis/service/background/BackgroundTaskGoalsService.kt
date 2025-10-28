package com.jervis.service.background

import com.jervis.domain.task.PendingTaskTypeEnum
import mu.KotlinLogging
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.yaml.snakeyaml.Yaml

data class TaskConfig(
    val goal: String,
    val qualifierSystemPrompt: String?,
    val qualifierUserPrompt: String?,
)

/**
 * Service for loading background task configuration from YAML.
 * Each pending task type has:
 * - goal: Instructions for the planner (used by AgentOrchestrator)
 * - qualifierSystemPrompt + qualifierUserPrompt: For quick pre-qualification (used by TaskQualificationService)
 */
@Service
class BackgroundTaskGoalsService {
    private val logger = KotlinLogging.logger {}
    private val tasks: Map<String, TaskConfig>

    init {
        tasks = loadConfiguration()
        logger.info { "Loaded configuration for ${tasks.size} background task types" }
    }

    /**
     * Get goal for a specific pending task type (used by AgentOrchestrator).
     * Returns null if no goal defined for this type.
     */
    fun getGoals(taskType: PendingTaskTypeEnum): String? = tasks[taskType.name]?.goal

    /**
     * Get qualifier prompts (systemPrompt + userPrompt) for a task type (used by TaskQualificationService).
     * Returns null if no qualifier prompts defined for this type.
     */
    fun getQualifierPrompts(taskType: PendingTaskTypeEnum): TaskConfig = tasks[taskType.name]!!

    private fun loadConfiguration(): Map<String, TaskConfig> =
        try {
            val resource = ClassPathResource("background-task-goals.yaml")
            val yaml = Yaml()
            val data: Map<String, Any> = yaml.load(resource.inputStream)

            val tasksRaw = data["tasks"] as? Map<String, Map<String, String>> ?: emptyMap()
            val tasksTyped =
                tasksRaw.mapValues { (_, value) ->
                    TaskConfig(
                        goal = value["goal"] ?: "",
                        qualifierSystemPrompt = value["qualifierSystemPrompt"],
                        qualifierUserPrompt = value["qualifierUserPrompt"],
                    )
                }

            logger.debug { "Loaded task configurations: ${tasksTyped.keys}" }
            tasksTyped
        } catch (e: Exception) {
            logger.error(e) { "Failed to load background-task-goals.yaml, using empty configuration" }
            emptyMap()
        }
}
