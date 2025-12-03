package com.jervis.dto

/**
 * Lifecycle state of a PendingTask.
 * Index-first pipeline, then qualification, then optional GPU dispatch.
 */
enum class PendingTaskStateEnum {
    NEW, // Task created, context can be merged
    INDEXING, // Fetching/normalizing content and writing to RAG
    INDEXED, // RAG is written
    READY_FOR_QUALIFICATION, // Awaiting qualifier claim
    QUALIFYING, // Claimed by qualifier, making decision
    QUALIFICATION_IN_PROGRESS, // Qualifier is processing (indexing to Graph/RAG with potential chunking)
    READY_FOR_GPU, // Qualification complete, complex task needs GPU execution
    DISPATCHED_GPU, // Delegated to strong model / GPU pipeline
}
