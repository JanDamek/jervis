package com.jervis.dto

/**
 * Lifecycle state of a PendingTask.
 * Index-first pipeline, then qualification, then optional GPU dispatch.
 */
enum class PendingTaskStateEnum {
    NEW, // Task created, context can be merged
    READY_FOR_QUALIFICATION, // Awaiting qualifier claim
    QUALIFYING, // Claimed by qualifier, making decision
    READY_FOR_GPU, // Qualification complete, complex task needs GPU execution
    DISPATCHED_GPU, // Delegated to strong model / GPU pipeline
    ERROR,
}
