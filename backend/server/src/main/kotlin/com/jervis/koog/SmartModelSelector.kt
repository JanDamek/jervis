package com.jervis.koog

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.jervis.service.token.TokenCountingService
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Smart Model Selector - Context-Aware LLM Selection.
 *
 * **Purpose:** Dynamically selects optimal Ollama model based on input content length.
 *
 * **Problem:**
 * - Small context → truncates large documents
 * - Large context → wastes RAM/VRAM on small tasks
 *
 * **Solution:** Tiered models on Ollama server. Takes base model name (e.g., `qwen3-coder-tool:30b`)
 * and inserts tier size: `qwen3-coder-tool-8k:30b`
 *
 * **Available Tiers:**
 * - GPU Safe: 4k, 8k, 16k, 32k
 * - RAM Spillover: 40k, 48k, 64k, 80k, 96k, 112k, 128k, 192k, 256k
 *
 * **Algorithm (Text Mode):**
 * 1. Count exact tokens using BPE tokenizer (jtokkit/tiktoken)
 * 2. Add output reserve (default 2k tokens minimum)
 * 3. Select smallest tier >= required tokens
 * 4. Insert tier into base model name: `qwen3-coder-tool:30b` → `qwen3-coder-tool-8k:30b`
 * 5. Return LLModel with dynamic ID and contextLength
 *
 * **Algorithm (Vision Mode):**
 * 1. Count text tokens (prompt)
 * 2. Estimate image tokens: (width × height) / compression_ratio
 * 3. Sum: text_tokens + image_tokens + output_reserve
 * 4. Select tier and insert into vision model name: `qwen3-vl:latest` → `qwen3-vl-tool-16k:latest`
 *
 * **Important:**
 * - Ollama models MUST exist on server (created via Modelfile)
 * - Modelfile MUST set `PARAMETER num_ctx {SIZE * 1024}`
 * - LLModel.contextLength MUST match Modelfile num_ctx
 */
@Service
class SmartModelSelector(
    private val tokenCountingService: TokenCountingService,
) {
    private val logger = KotlinLogging.logger {}

    companion object {
        /**
         * Available tiers in thousands (k).
         * Sorted ascending for binary search.
         *
         * GPU Safe (< 32GB VRAM):
         * - 4k, 8k, 16k, 32k
         *
         * RAM Spillover (requires more RAM):
         * - 40k, 48k, 64k, 80k, 96k, 112k, 128k, 192k, 256k
         */
        private val AVAILABLE_TIERS = listOf(4, 8, 16, 32, 40, 48, 64, 80, 96, 112, 128, 192, 256)

        /**
         * Minimum output reserve in tokens.
         * Used as fallback when caller doesn't provide explicit value.
         */
        private const val MIN_OUTPUT_RESERVE = 2000

        /**
         * Vision model image token estimation.
         *
         * Qwen2-VL uses dynamic resolution with patch size 14×14.
         * Token count ≈ (width × height) / (patch_size² × compression_ratio)
         *
         * Conservative estimates:
         * - 512×512 image ≈ 500-700 tokens
         * - 1024×1024 image ≈ 2000-2500 tokens
         * - 2048×2048 image ≈ 8000-10000 tokens
         *
         * We use compression ratio of 400 (14×14 patch × ~2 compression).
         */
        private const val IMAGE_TOKEN_COMPRESSION_RATIO = 400
    }

    /**
     * Image metadata for vision token estimation.
     */
    data class ImageMetadata(
        val widthPixels: Int,
        val heightPixels: Int,
        val format: String, // "png", "jpg", etc.
    ) {
        /**
         * Estimate tokens this image will consume in vision model.
         */
        fun estimateTokens(): Int {
            val pixels = widthPixels * heightPixels
            return (pixels / IMAGE_TOKEN_COMPRESSION_RATIO).coerceAtLeast(100)
        }
    }

    /**
     * Select optimal TEXT model based on input content length.
     *
     * @param baseModelName Base model name (e.g., "qwen3-coder-tool:30b" or "qwen3-tool:30b")
     * @param inputContent The text content to process
     * @return LLModel configured for Koog framework with tier inserted into model name
     */
    fun selectModel(
        baseModelName: BaseModelTypeEnum,
        inputContent: String = "",
    ): LLModel {
        // Count actual tokens using BPE tokenizer (jtokkit)
        val inputTokens = if (inputContent.isNotBlank()) tokenCountingService.countTokens(inputContent) else 8196
        val totalTokensNeeded = inputTokens + (inputTokens * 1.5).toInt().coerceAtLeast(2000)

        // Find smallest tier that fits
        val selectedTierK =
            AVAILABLE_TIERS.firstOrNull { tierK ->
                (tierK * 1024) >= totalTokensNeeded
            } ?: AVAILABLE_TIERS.last() // Fallback to max tier (256k)

        val contextLength = selectedTierK * 1024
        val modelId = insertTierIntoModelName(baseModelName.modelName, selectedTierK)

        logger.debug {
            buildString {
                append("SmartModelSelector [TEXT] | ")
                append("baseModel=$baseModelName | ")
                append("inputChars=${inputContent.length} | ")
                append("inputTokens=$inputTokens | ")
                append("totalNeeded=$totalTokensNeeded | ")
                append("selectedTier=${selectedTierK}k | ")
                append("modelId=$modelId | ")
                append("contextLength=$contextLength")
            }
        }

        return LLModel(
            provider = LLMProvider.Ollama,
            id = modelId,
            capabilities =
                listOf(
                    LLMCapability.Tools,
                    LLMCapability.Schema.JSON.Basic,
                    LLMCapability.Temperature,
                    LLMCapability.ToolChoice,
                    LLMCapability.Document,
                ),
            contextLength = contextLength.toLong(),
        )
    }

    enum class BaseModelTypeEnum(
        val modelName: String,
    ) {
        REASONING("qwen3-tool:30b"),
        AGENT("qwen3-coder-tool:30b"),
        PLANNER("qwen3-tool:14b"), // Lightweight model for planning
        VL("qwen3-vl-tool:30b"),
    }

    /**
     * Select optimal VISION model based on text + image content.
     *
     * @param baseModelName Base vision model name (e.g., "qwen3-vl:latest")
     * @param textPrompt Text portion of the prompt
     * @param images List of image metadata (resolution info)
     * @param outputReserve Tokens to reserve for model output (default: 2000)
     * @return LLModel configured for Koog framework with tier inserted
     */
    fun selectVisionModel(
        baseModelName: String,
        textPrompt: String,
        images: List<ImageMetadata>,
        outputReserve: Int = MIN_OUTPUT_RESERVE,
    ): LLModel {
        val textTokens = tokenCountingService.countTokens(textPrompt)
        val imageTokens = images.sumOf { it.estimateTokens() }
        val totalTokensNeeded = textTokens + imageTokens + outputReserve
        val selectedTierK =
            AVAILABLE_TIERS.firstOrNull { tierK ->
                (tierK * 1024) >= totalTokensNeeded
            } ?: AVAILABLE_TIERS.last()

        val contextLength = selectedTierK * 1024
        val modelId = insertTierIntoModelName(baseModelName, selectedTierK)

        logger.debug {
            buildString {
                append("SmartModelSelector [VISION] | ")
                append("baseModel=$baseModelName | ")
                append("textChars=${textPrompt.length} | ")
                append("textTokens=$textTokens | ")
                append("imageCount=${images.size} | ")
                append("imageTokens=$imageTokens | ")
                images.forEachIndexed { idx, img ->
                    append("img[$idx]=${img.widthPixels}x${img.heightPixels}(~${img.estimateTokens()}t) | ")
                }
                append("outputReserve=$outputReserve | ")
                append("totalNeeded=$totalTokensNeeded | ")
                append("selectedTier=${selectedTierK}k | ")
                append("modelId=$modelId | ")
                append("contextLength=$contextLength")
            }
        }

        return LLModel(
            provider = LLMProvider.Ollama,
            id = modelId,
            capabilities =
                listOf(
                    LLMCapability.Vision.Image,
                    LLMCapability.Tools,
                    LLMCapability.ToolChoice,
                    LLMCapability.Temperature,
                    LLMCapability.Document,
                    LLMCapability.Schema.JSON.Basic,
                ),
            contextLength = contextLength.toLong(),
        )
    }

    /**
     * Insert tier size into base model name.
     *
     * Pattern: `qwen3-coder-tool:30b` + 8k → `qwen3-coder-tool-8k:30b`
     * Pattern: `qwen3-vl:latest` + 16k → `qwen3-vl-tool-16k:latest`
     *
     * @param baseModelName Base model name with tag (e.g., "qwen3-coder-tool:30b" or "qwen3-vl:latest")
     * @param tierK Tier size in thousands (e.g., 8 for 8k)
     * @return Model name with tier inserted before tag
     */
    private fun insertTierIntoModelName(
        baseModelName: String,
        tierK: Int,
    ): String {
        val parts = baseModelName.split(":")
        return if (parts.size == 2) {
            val baseName = parts[0] // "qwen3-coder-tool", "qwen3-tool", "qwen3-vl"
            val tag = parts[1] // "30b", "latest"

            // For vision models, add "-tool" suffix
            val nameWithTier =
                if (baseName.endsWith("-vl")) {
                    "$baseName-tool-${tierK}k" // "qwen3-vl" → "qwen3-vl-tool-8k"
                } else {
                    "$baseName-${tierK}k" // "qwen3-coder-tool" → "qwen3-coder-tool-8k"
                }

            "$nameWithTier:$tag"
        } else {
            // Fallback: no tag found, just append tier
            "$baseModelName-${tierK}k"
        }
    }
}
