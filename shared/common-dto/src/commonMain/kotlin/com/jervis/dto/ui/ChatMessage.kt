package com.jervis.dto.ui

import kotlinx.serialization.Serializable

/** Simple chat message model for Mobile UIs. */
@Serializable
data class ChatMessage(
    val from: Sender,
    val text: String,
    val contextId: String? = null,
    val timestamp: String? = null,
    val messageType: MessageType = MessageType.FINAL,
    val metadata: Map<String, String> = emptyMap(),
    val workflowSteps: List<WorkflowStep> = emptyList(),
) {
    @Serializable
    enum class Sender { Me, Assistant }

    @Serializable
    enum class MessageType {
        USER_MESSAGE, // User's message (for synchronization across clients)
        PROGRESS,     // Intermediate progress update
        FINAL,        // Final answer
        ERROR,        // Error message (displayed with red styling)
    }

    @Serializable
    data class WorkflowStep(
        val node: String,           // Node name (intake, evidence_pack, respond, etc.)
        val label: String,          // Human-readable label (Analýza úlohy, Shromažďování kontextu)
        val status: StepStatus = StepStatus.COMPLETED,
        val tools: List<String> = emptyList(),  // Tools used in this step
    )

    @Serializable
    enum class StepStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
    }
}
