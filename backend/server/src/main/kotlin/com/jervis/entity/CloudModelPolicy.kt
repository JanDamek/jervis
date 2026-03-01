package com.jervis.entity

/**
 * OpenRouter tier levels for cloud model routing.
 * Controls which OpenRouter queues are available for a project.
 */
enum class OpenRouterTier {
    /** No OpenRouter — local GPU only (wait for GPU if busy) */
    NONE,
    /** Free cloud models only (e.g. qwen3-30b:free) */
    FREE,
    /** Standard paid models (e.g. Haiku, GPT-4o-mini) */
    PAID_LOW,
    /** Thinking/reasoning paid models (e.g. Sonnet, o3-mini) */
    PAID_HIGH,
}

data class CloudModelPolicy(
    val autoUseAnthropic: Boolean = false,
    val autoUseOpenai: Boolean = false,
    val autoUseGemini: Boolean = false,
    val maxOpenRouterTier: OpenRouterTier = OpenRouterTier.NONE,
)
