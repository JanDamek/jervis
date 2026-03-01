package com.jervis.dto.openrouter

import kotlinx.serialization.Serializable

/**
 * Global OpenRouter configuration stored as singleton settings document.
 *
 * Contains API credentials, model filters, and a prioritized list
 * of available models that the orchestrator, chat, and KB can use.
 *
 * Routing logic:
 * - Requests are first served by local P40 GPU (Ollama)
 * - When local queue is full or context exceeds 48k tokens → route via OpenRouter
 * - Per-project rules determine whether paid cloud models are allowed
 */
@Serializable
data class OpenRouterSettingsDto(
    /** OpenRouter API key (sk-or-v1-...) */
    val apiKey: String = "",

    /** Base URL for OpenRouter API (default: https://openrouter.ai/api/v1) */
    val apiBaseUrl: String = "https://openrouter.ai/api/v1",

    /** Whether OpenRouter routing is globally enabled */
    val enabled: Boolean = false,

    /** Filters applied when building the available model list */
    val filters: OpenRouterFiltersDto = OpenRouterFiltersDto(),

    /** Ordered list of models available for routing (priority = list index, 0 = highest) */
    val models: List<OpenRouterModelEntryDto> = emptyList(),

    /** Maximum monthly budget in USD (0 = unlimited) */
    val monthlyBudgetUsd: Double = 0.0,

    /** Fallback strategy when preferred model is unavailable */
    val fallbackStrategy: OpenRouterFallbackStrategy = OpenRouterFallbackStrategy.NEXT_IN_LIST,

    /** Named model queues for queue-based routing (CHAT, FREE, ORCHESTRATOR, etc.) */
    val modelQueues: List<ModelQueueDto> = emptyList(),
)

/**
 * Filters to narrow down which models are available from OpenRouter.
 */
@Serializable
data class OpenRouterFiltersDto(
    /** Only include models from these providers (empty = all providers) */
    val allowedProviders: List<String> = emptyList(),

    /** Exclude models from these providers */
    val blockedProviders: List<String> = emptyList(),

    /** Minimum context window size in tokens (0 = no minimum) */
    val minContextLength: Int = 0,

    /** Maximum price per 1M input tokens in USD (0 = no limit) */
    val maxInputPricePerMillion: Double = 0.0,

    /** Maximum price per 1M output tokens in USD (0 = no limit) */
    val maxOutputPricePerMillion: Double = 0.0,

    /** Only include models that support tool/function calling */
    val requireToolSupport: Boolean = false,

    /** Only include models that support streaming */
    val requireStreaming: Boolean = true,

    /** Model name search pattern (e.g. "gpt-4", "claude", "llama") */
    val modelNameFilter: String = "",
)

/**
 * A single model entry in the prioritized routing list.
 *
 * The list position determines preference order:
 * - Position 0 = first choice
 * - Position N = fallback after 0..N-1 fail or are unavailable
 */
@Serializable
data class OpenRouterModelEntryDto(
    /** OpenRouter model ID (e.g. "openai/gpt-4-turbo", "anthropic/claude-3.5-sonnet") */
    val modelId: String,

    /** Human-readable display name */
    val displayName: String = "",

    /** Whether this entry is enabled (disabled entries are skipped during routing) */
    val enabled: Boolean = true,

    /** Maximum context tokens for this model (informational, from OpenRouter catalog) */
    val maxContextTokens: Int = 0,

    /** Price per 1M input tokens (informational) */
    val inputPricePerMillion: Double = 0.0,

    /** Price per 1M output tokens (informational) */
    val outputPricePerMillion: Double = 0.0,

    /** Use cases where this model is preferred */
    val preferredFor: List<OpenRouterModelUseCase> = emptyList(),

    /** Maximum tokens to request per call (0 = model default) */
    val maxOutputTokens: Int = 0,

    /** Whether this is a free-tier model (e.g. ":free" suffix on OpenRouter).
     * Free models are preferred for background tasks to avoid costs. */
    val free: Boolean = false,
)

/**
 * Intended use case for a model in the routing list.
 * The orchestrator uses these to pick the best model for each task type.
 */
@Serializable
enum class OpenRouterModelUseCase {
    /** General chat and Q&A */
    CHAT,

    /** Code generation and editing */
    CODING,

    /** Architecture and design reasoning */
    REASONING,

    /** Large context processing (>48k tokens) */
    LARGE_CONTEXT,

    /** Knowledge base operations (summarization, extraction) */
    KNOWLEDGE_BASE,

    /** Task orchestration and planning */
    ORCHESTRATION,
}

/**
 * Fallback strategy when the preferred model is unavailable.
 */
@Serializable
enum class OpenRouterFallbackStrategy {
    /** Try the next model in the priority list */
    NEXT_IN_LIST,

    /** Let OpenRouter auto-route to the best available model */
    OPENROUTER_AUTO,

    /** Fail the request (return to local queue) */
    FAIL,
}

/**
 * Request to update OpenRouter settings.
 * Only non-null fields are applied (partial update).
 */
@Serializable
data class OpenRouterSettingsUpdateDto(
    val apiKey: String? = null,
    val apiBaseUrl: String? = null,
    val enabled: Boolean? = null,
    val filters: OpenRouterFiltersDto? = null,
    val models: List<OpenRouterModelEntryDto>? = null,
    val monthlyBudgetUsd: Double? = null,
    val fallbackStrategy: OpenRouterFallbackStrategy? = null,
    val modelQueues: List<ModelQueueDto>? = null,
)

/**
 * Named queue of models with fallback ordering.
 *
 * Each queue represents a use case (CHAT, FREE, ORCHESTRATOR, etc.) with
 * an ordered list of models. The router iterates the list top-to-bottom:
 * first available model (local GPU free, or cloud always available) is used.
 */
@Serializable
data class ModelQueueDto(
    /** Queue name (e.g. "CHAT", "FREE", "ORCHESTRATOR", "LARGE_CONTEXT", "CODING") */
    val name: String,

    /** Ordered list of models in this queue (index 0 = highest priority) */
    val models: List<QueueModelEntryDto> = emptyList(),

    /** Whether this queue is enabled */
    val enabled: Boolean = true,
)

/**
 * A single model entry within a named queue.
 *
 * Can represent either a local GPU model (via Ollama router) or a cloud model
 * (via OpenRouter). The router checks local availability before falling back
 * to the next model in the queue.
 */
@Serializable
data class QueueModelEntryDto(
    /** Model identifier. "p40" for local GPU, or OpenRouter ID (e.g. "anthropic/claude-sonnet-4") */
    val modelId: String,

    /** true = local GPU (via Ollama router), false = cloud (via OpenRouter) */
    val isLocal: Boolean = false,

    /** Maximum context tokens this model can handle */
    val maxContextTokens: Int = 32_000,

    /** Whether this entry is enabled */
    val enabled: Boolean = true,

    /** Human-readable label for UI */
    val label: String = "",
)

/**
 * Response from OpenRouter catalog query (available models from their API).
 */
@Serializable
data class OpenRouterCatalogModelDto(
    val id: String,
    val name: String,
    val contextLength: Int = 0,
    val inputPricePerMillion: Double = 0.0,
    val outputPricePerMillion: Double = 0.0,
    val supportsTools: Boolean = false,
    val supportsStreaming: Boolean = true,
    val provider: String = "",
)
