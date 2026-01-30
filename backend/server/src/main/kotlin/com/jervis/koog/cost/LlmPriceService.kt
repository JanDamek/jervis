package com.jervis.koog.cost

import org.springframework.stereotype.Service

/**
 * Price configuration for various LLM models.
 * Prices are in USD per 1M tokens.
 */
@Service
class LlmPriceService {
    data class ModelPrice(val inputPrice: Double, val outputPrice: Double)

    private val prices = mapOf(
        "claude-3-5-sonnet-20241022" to ModelPrice(3.0, 15.0),
        "claude-3-5-haiku-20241022" to ModelPrice(0.25, 1.25),
        "gpt-4o" to ModelPrice(2.5, 10.0),
        "gpt-4o-mini" to ModelPrice(0.15, 0.60),
        "gemini-1.5-pro" to ModelPrice(1.25, 3.75),
        "gemini-1.5-flash" to ModelPrice(0.075, 0.3),
        "qwen" to ModelPrice(0.0, 0.0), // Local model is free
    )

    fun getPrice(modelId: String): ModelPrice? {
        return prices.entries.find { modelId.contains(it.key) }?.value
    }

    /**
     * Calculate cost for a request.
     * @return Cost in USD
     */
    fun calculateCost(modelId: String, inputTokens: Int, outputTokens: Int): Double {
        val price = getPrice(modelId) ?: return 0.0
        return (inputTokens * price.inputPrice + outputTokens * price.outputPrice) / 1_000_000.0
    }

    /**
     * Check if a project has enough budget for a request.
     */
    fun hasBudget(monthlyLimit: Double, monthlySpent: Double, estimatedCost: Double): Boolean {
        if (monthlyLimit <= 0.0) return true // Unlimited
        return (monthlySpent + estimatedCost) <= monthlyLimit
    }

    fun isCloudModel(modelId: String): Boolean {
        if (modelId.contains("qwen")) return false
        return prices.keys.any { modelId.contains(it) }
    }

    fun getProvider(modelId: String): String {
        return when {
            modelId.contains("claude") -> "anthropic"
            modelId.contains("gpt") -> "openai"
            modelId.contains("gemini") -> "google"
            modelId.contains("qwen") -> "ollama"
            else -> "unknown"
        }
    }
}
