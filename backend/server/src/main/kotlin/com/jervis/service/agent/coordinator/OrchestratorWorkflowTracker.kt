package com.jervis.service.agent.coordinator

import com.jervis.dto.ui.ChatMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory tracker for orchestrator workflow steps.
 * Accumulates progress events (node transitions) per task to build workflow history.
 * When task completes, workflow steps are attached to the final chat message.
 *
 * Similar pattern to OrchestratorHeartbeatTracker but tracks structured workflow data.
 */
@Component
class OrchestratorWorkflowTracker {
    private val workflowSteps = ConcurrentHashMap<String, MutableList<ChatMessage.WorkflowStep>>()

    /** Node name to Czech label mapping (same as MainViewModel) */
    private val nodeLabels = mapOf(
        "intake" to "Analýza úlohy",
        "evidence" to "Shromažďování kontextu",
        "evidence_pack" to "Shromažďování kontextu",
        "plan" to "Plánování",
        "plan_steps" to "Plánování kroků",
        "execute" to "Provádění",
        "execute_step" to "Provádění kroku",
        "evaluate" to "Vyhodnocení",
        "finalize" to "Dokončení",
        "respond" to "Generování odpovědi",
        "clarify" to "Upřesnění",
        "decompose" to "Dekompozice na cíle",
        "select_goal" to "Výběr cíle",
        "advance_step" to "Další krok",
        "advance_goal" to "Další cíl",
        "git_operations" to "Git operace",
        "report" to "Generování reportu",
    )

    /**
     * Add a workflow step for a task.
     * If the same node is visited multiple times, marks previous as COMPLETED
     * and adds new instance as IN_PROGRESS.
     */
    fun addStep(taskId: String, node: String, tools: List<String> = emptyList()) {
        val steps = workflowSteps.getOrPut(taskId) { mutableListOf() }
        val label = nodeLabels[node] ?: node

        // Mark all existing IN_PROGRESS steps as COMPLETED
        steps.replaceAll { step ->
            if (step.status == ChatMessage.StepStatus.IN_PROGRESS) {
                step.copy(status = ChatMessage.StepStatus.COMPLETED)
            } else step
        }

        // Add new step (or update existing if same node visited again)
        val existingIdx = steps.indexOfFirst { it.node == node }
        if (existingIdx < 0) {
            steps.add(
                ChatMessage.WorkflowStep(
                    node = node,
                    label = label,
                    status = ChatMessage.StepStatus.IN_PROGRESS,
                    tools = tools,
                ),
            )
        } else {
            // Node visited again - replace with updated step
            steps[existingIdx] = ChatMessage.WorkflowStep(
                node = node,
                label = label,
                status = ChatMessage.StepStatus.IN_PROGRESS,
                tools = tools,
            )
        }
    }

    /**
     * Get workflow steps for a task and mark all as COMPLETED.
     * Returns JSON-serialized string suitable for ChatMessageDocument.metadata.
     */
    fun getStepsAsJson(taskId: String): String? {
        val steps = workflowSteps[taskId] ?: return null
        if (steps.isEmpty()) return null

        // Mark all steps as COMPLETED
        val finalSteps = steps.map { step ->
            if (step.status == ChatMessage.StepStatus.IN_PROGRESS) {
                step.copy(status = ChatMessage.StepStatus.COMPLETED)
            } else step
        }

        return Json.encodeToString(finalSteps)
    }

    /**
     * Clear workflow steps for a task (after final message is saved).
     */
    fun clearSteps(taskId: String) {
        workflowSteps.remove(taskId)
    }

    /**
     * Deserialize workflow steps from JSON string.
     * Used when loading chat history to populate workflowSteps field.
     */
    companion object {
        fun parseStepsFromJson(json: String?): List<ChatMessage.WorkflowStep> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                Json.decodeFromString<List<ChatMessage.WorkflowStep>>(json)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
