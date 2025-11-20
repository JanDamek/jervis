package com.jervis.service.background

import com.jervis.dto.PendingTaskTypeEnum
import mu.KotlinLogging
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.yaml.snakeyaml.Yaml

/**
 * Service for loading background task configuration from YAML.
 * Each pending task type has:
 * - goal: Instructions for the planner (used by AgentOrchestrator)
 * - qualifierSystemPrompt + qualifierUserPrompt: For quick pre-qualification (used by TaskQualificationService)
 */
@Service
class BackgroundTaskGoalsService {
    private val logger = KotlinLogging.logger {}
    private val tasks: Map<PendingTaskTypeEnum, TaskConfig>

    init {
        tasks = loadConfiguration()
        validateAllEnumsHaveConfiguration()
        logger.info { "Loaded configuration for ${tasks.size} background task types" }
    }

    /**
     * Get a goal for a specific pending task type (used by AgentOrchestrator).
     */
    fun getGoals(taskType: PendingTaskTypeEnum): String =
        requireNotNull(tasks[taskType]) {
            "Missing configuration for task type: $taskType"
        }.goal

    /**
     * Get qualifier prompts (systemPrompt + userPrompt) for a task type (used by TaskQualificationService).
     */
    fun getQualifierPrompts(taskType: PendingTaskTypeEnum): TaskConfig =
        requireNotNull(tasks[taskType]) {
            "Missing configuration for task type: $taskType"
        }

    private fun validateAllEnumsHaveConfiguration() {
        val missingTypes = PendingTaskTypeEnum.entries.filter { it !in tasks.keys }
        check(missingTypes.isEmpty()) {
            "Missing configuration for PendingTaskTypeEnum values: ${missingTypes.joinToString()}"
        }
    }

    private fun loadConfiguration(): Map<PendingTaskTypeEnum, TaskConfig> =
        try {
            val resource = ClassPathResource("background-task-goals.yaml")
            val yaml = Yaml()
            val data = yaml.load<Map<String, Any?>>(resource.inputStream) ?: emptyMap()

            val tasksSection =
                when (val tasks = data["tasks"]) {
                    is Map<*, *> -> tasks.filterKeys { it is String }.mapKeys { it.key as String }
                    else -> emptyMap()
                }
            val tasksTyped =
                tasksSection.entries
                    .map { (k, v) ->
                        val key = PendingTaskTypeEnum.valueOf(k.toString())
                        val valueMap: Map<String, String> =
                            (v as? Map<*, *>)
                                ?.mapNotNull { (ik, iv) ->
                                    val innerKey = ik?.toString() ?: return@mapNotNull null
                                    val innerVal = (iv as? String) ?: return@mapNotNull null
                                    innerKey to innerVal
                                }?.toMap() ?: emptyMap()

                        val goal =
                            requireNotNull(valueMap["goal"]) {
                                "Missing 'goal' for task type: $key"
                            }
                        val qualifierSystemPrompt =
                            requireNotNull(valueMap["qualifierSystemPrompt"]) {
                                "Missing 'qualifierSystemPrompt' for task type: $key"
                            }
                        val qualifierUserPrompt =
                            requireNotNull(valueMap["qualifierUserPrompt"]) {
                                "Missing 'qualifierUserPrompt' for task type: $key"
                            }

                        key to
                            TaskConfig(
                                goal = goal,
                                qualifierSystemPrompt = qualifierSystemPrompt,
                                qualifierUserPrompt = qualifierUserPrompt,
                            )
                    }.toMap()

            logger.debug { "Loaded task configurations: ${tasksTyped.keys}" }
            tasksTyped
        } catch (e: Exception) {
            logger.error(e) { "Failed to load background-task-goals.yaml" }
            throw IllegalStateException("Cannot start application without valid background-task-goals.yaml", e)
        }

    data class TaskConfig(
        val goal: String,
        val qualifierSystemPrompt: String,
        val qualifierUserPrompt: String,
    )
}
