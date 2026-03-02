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
    /** Paid models (e.g. Haiku, GPT-4o-mini) — was PAID_LOW */
    PAID,
    /** Premium reasoning models (e.g. Sonnet, o3-mini) — was PAID_HIGH */
    PREMIUM,
}

data class CloudModelPolicy(
    val autoUseAnthropic: Boolean = false,
    val autoUseOpenai: Boolean = false,
    val autoUseGemini: Boolean = false,
    val maxOpenRouterTier: OpenRouterTier = OpenRouterTier.FREE,
)
