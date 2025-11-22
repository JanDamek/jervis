package com.jervis.domain.model

data class ProviderCapabilities(
    val provider: ModelProviderEnum,
    val endpoint: String,
    val maxConcurrentRequests: Int,
    val executionMode: ExecutionMode,
)

enum class ExecutionMode {
    /**
     * NONBLOCKING mode (CPU):
     * - Always runs immediately
     * - Never blocks
     * - Never yields to other requests
     * - Used for qualifiers, embeddings
     */
    NONBLOCKING,

    /**
     * INTERRUPTIBLE mode (GPU):
     * - Foreground requests have priority
     * - Background requests yield when foreground waits
     * - Most powerful models run here
     * - Used for main agent inference
     */
    INTERRUPTIBLE,
}
