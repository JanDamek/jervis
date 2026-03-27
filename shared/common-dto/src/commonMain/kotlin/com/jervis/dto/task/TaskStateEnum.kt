package com.jervis.dto.task

/**
 * Lifecycle state of a Task.
 *
 * Pipeline: NEW → INDEXING → QUEUED → PROCESSING → DONE
 *
 * States describe what the TASK is doing, not who processes it.
 */
enum class TaskStateEnum {
    NEW,           // Scheduled task waiting for scheduledAt
    INDEXING,      // KB processing: text extraction, embedding, graph nodes (atomic claim via claimedAt)
    QUEUED,        // Ready for orchestrator pickup (in execution queue)
    PROCESSING,    // Orchestrator actively working (agentic loop running)
    CODING,        // K8s coding agent Job dispatched, waiting for completion
    USER_TASK,     // Waiting for user input/decision
    BLOCKED,       // Waiting for sub-tasks or dependency tasks (blockedByTaskIds) to complete
    DONE,          // Terminal — completed or no action needed
    ERROR,         // Terminal — failed
}
