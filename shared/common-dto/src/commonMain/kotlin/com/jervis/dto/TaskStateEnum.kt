package com.jervis.dto

/**
 * Lifecycle state of a PendingTask.
 * Index-first pipeline, then qualification, then optional GPU dispatch.
 */
enum class TaskStateEnum {
    NEW, // Task created, context can be merged
    READY_FOR_QUALIFICATION, // Awaiting qualifier claim
    QUALIFYING, // Claimed by qualifier, making decision
    READY_FOR_GPU, // Qualification complete, complex task needs GPU execution
    DISPATCHED_GPU, // Delegated to strong model / GPU pipeline
    PYTHON_ORCHESTRATING, // Dispatched to Python orchestrator (LangGraph), awaiting result
    WAITING_FOR_AGENT, // Coding agent K8s Job dispatched, waiting for completion
    USER_TASK, // Waiting for user input/decision
    DONE, // Terminal state — qualification complete, no orchestrator action needed (info_only, simple_action)
    ERROR,
}
