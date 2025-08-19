package com.jervis.service.setting

import com.jervis.domain.model.ModelProvider
import com.jervis.entity.mongo.SettingDocument
import com.jervis.entity.mongo.SettingType
import com.jervis.events.SettingsChangeEvent
import com.jervis.repository.mongo.SettingMongoRepository
import kotlinx.coroutines.runBlocking
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class SettingService(
    private val settingRepository: SettingMongoRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val environment: org.springframework.core.env.Environment,
) {
    // -------- Unified models config (primary/fallback by usage) --------
    private val MODELS_CONFIG_VALUE = "models.config"

    private enum class Role { PRIMARY, FALLBACK }

    private data class ModelEntry(
        val model: String,
        val provider: ModelProvider?,
        val usage: String,
        val tokens: Int?,
        val maxTokens: Int?,
        val role: Role?,
    )

    private data class ResolvedModel(
        val provider: ModelProvider,
        val model: String,
        val maxTokens: Int?,
        val role: String? = null,
    )

    // Parse models.config content supporting JSON array of objects or CSV-like lines.
    private fun parseModelsConfig(raw: String): List<ModelEntry> {
        if (raw.isBlank()) return emptyList()
        val text = raw.trim()
        return try {
            if (text.startsWith("[") || text.startsWith("{")) {
                // Try JSON array first
                val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                val list: List<Map<String, Any?>> = if (text.startsWith("{")) listOf(mapper.readValue(text, Map::class.java) as Map<String, Any?>) else mapper.readValue(text, mapper.typeFactory.constructCollectionType(List::class.java, Map::class.java))
                list.mapNotNull { m ->
                    val model = (m["model"] as? String)?.trim().orEmpty()
                    val providerStr = (m["provider"] as? String)?.trim()?.uppercase()
                    val usage = ((m["usage"] as? String) ?: (m["purpose"] as? String) ?: "").trim()
                    if (model.isBlank() || usage.isBlank()) return@mapNotNull null
                    val provider = providerStr?.let { runCatching { ModelProvider.valueOf(it) }.getOrNull() }
                    val tokens = (m["tokens"] as? Number)?.toInt()
                    val maxTokens = ((m["maxTokens"] ?: m["max-token"]) as? Number)?.toInt()
                    val role = ((m["role"] as? String)?.lowercase())?.let { if (it == "primary") Role.PRIMARY else if (it == "fallback") Role.FALLBACK else null }
                    ModelEntry(model, provider, usage, tokens, maxTokens, role)
                }
            } else {
                // CSV-like: model, provider, maxTokens, usage, role (order flexible), allow numbers for tokens/maxTokens
                val providers = setOf("OPENAI", "ANTHROPIC", "OLLAMA", "LM_STUDIO")
                text.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .mapNotNull { line ->
                        val parts = line.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        if (parts.isEmpty()) return@mapNotNull null
                        val model = parts.first()
                        var provider: ModelProvider? = null
                        var usage: String? = null
                        var tokens: Int? = null
                        var maxTokens: Int? = null
                        var role: Role? = null
                        for (i in 1 until parts.size) {
                            val p = parts[i]
                            val up = p.uppercase()
                            when {
                                providers.contains(up) -> provider = runCatching { ModelProvider.valueOf(up) }.getOrNull()
                                p.equals("primary", true) -> role = Role.PRIMARY
                                p.equals("fallback", true) -> role = Role.FALLBACK
                                p.matches(Regex("\\d+")) -> {
                                    val n = p.toInt()
                                    if (maxTokens == null) maxTokens = n else if (tokens == null) tokens = n
                                }
                                else -> usage = p
                            }
                        }
                        if (usage.isNullOrBlank()) return@mapNotNull null
                        ModelEntry(model, provider, usage!!, tokens, maxTokens, role)
                    }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readModelsConfigFromProperties(): String {
        // Read YAML list only: models[0], models[1], ...
        val lines = mutableListOf<String>()
        var i = 0
        while (i < 1000) {
            val v = environment.getProperty("models[$i]")?.trim()
            if (v == null) break
            if (v.isNotEmpty()) lines.add(v)
            i++
        }
        return lines.joinToString("\n")
    }

    private fun resolveForUsageFromConfig(usage: String): ResolvedModel? {
        val raw = readModelsConfigFromProperties()
        val entries = parseModelsConfig(raw)
            .filter { it.usage.equals(usage, ignoreCase = true) }
            .filter { it.provider != null } // require provider specified in YAML
        if (entries.isEmpty()) return null
        val primaries = entries.filter { it.role == Role.PRIMARY }
        val fallbacks = entries.filter { it.role == Role.FALLBACK }
        val unspecified = entries.filter { it.role == null }
        val chosenPrimaries = when {
            primaries.isNotEmpty() -> primaries
            fallbacks.isNotEmpty() -> unspecified
            unspecified.size == 1 -> unspecified
            unspecified.size > 1 -> listOf(unspecified.first())
            else -> emptyList()
        }
        val first = chosenPrimaries.firstOrNull() ?: fallbacks.firstOrNull() ?: entries.first()
        val provider = first.provider!!
        return ResolvedModel(provider = provider, model = first.model, maxTokens = first.maxTokens, role = first.role?.name?.lowercase())
    }
    // Batch mode for controlling event publishing
    private var batchMode = false
    private val batchChangedSettings = mutableSetOf<String>()
    private var batchChangeType = SettingsChangeEvent.ChangeType.GENERAL
    // Properties with direct database access
    var openaiApiKey: String
        get() = runBlocking { getStringValue(OPENAI_API_KEY_VALUE, "") }
        set(value) = runBlocking { saveValue(OPENAI_API_KEY_VALUE, value, SettingType.STRING) }

    var startupMinimize: Boolean
        get() = runBlocking { getBooleanValue(START_UP_MINIMIZE, false) }
        set(value) = runBlocking { saveBooleanSetting(START_UP_MINIMIZE, value) }

    // Private constants - pouze SettingService může používat
    private val OLLAMA_URL: String = "ollama_url"
    private val LLM_STUDIO_URL: String = "lm_studio_url"
    private val USE_OLLAMA: String = "use_ollama"
    private val USE_LM_STUDIO: String = "use_lm_studio"
    private val ANTHROPIC_API_KEY_VALUE = "anthropic_api_key"
    private val OPENAI_API_KEY_VALUE = "openai_api_key"

    // Constants for external model settings
    private val EMBEDDING_MODEL_NAME_VALUE = "embedding_model"
    private val EMBEDDING_TYPE_NAME_VALUE = "embedding_type" // legacy key, kept for compatibility
    private val EMBEDDING_PROVIDER_NAME_VALUE = "embedding.provider" // new key
    private val EMBEDDING_MAX_TOKENS_VALUE = "embedding.max.tokens" // new key
    // New embedding/translation model keys (LM Studio defaults)
    private val EMBEDDING_TEXT_MODEL_NAME_VALUE = "embedding.model.text"
    private val EMBEDDING_TEXT_MAX_TOKENS_VALUE = "embedding.model.text.max.tokens"
    private val EMBEDDING_CODE_MODEL_NAME_VALUE = "embedding.model.code"
    private val EMBEDDING_CODE_MAX_TOKENS_VALUE = "embedding.model.code.max.tokens"
    private val TRANSLATION_QUICK_MODEL_NAME_VALUE = "translation.model.quick"
    private val TRANSLATION_QUICK_MAX_TOKENS_VALUE = "translation.model.quick.max.tokens"
    private val TRANSLATION_QUICK_MAX_TOKENS_EXCEPTIONAL_VALUE = "translation.model.quick.max.tokens.exceptional"

    // Ollama defaults (optimized for ~20 GB RAM)
    private val OLLAMA_TEXT_EMBED_MODEL_NAME_VALUE = "ollama.embedding.model.text"
    private val OLLAMA_TEXT_EMBED_MAX_TOKENS_VALUE = "ollama.embedding.model.text.max.tokens"
    private val OLLAMA_CODE_EMBED_MODEL_NAME_VALUE = "ollama.embedding.model.code"
    private val OLLAMA_CODE_EMBED_MAX_TOKENS_VALUE = "ollama.embedding.model.code.max.tokens"
    private val OLLAMA_TRANSLATION_MODEL_NAME_VALUE = "ollama.translation.model"
    private val OLLAMA_TRANSLATION_MAX_TOKENS_VALUE = "ollama.translation.model.max.tokens"

    // Model settings - type and name are stored separately
    private val MODEL_SIMPLE_TYPE_VALUE = "external_model_simple_type"
    private val MODEL_SIMPLE_NAME_VALUE = "external_model_simple_name"
    private val MODEL_COMPLEX_TYPE_VALUE = "external_model_complex_type"
    private val MODEL_COMPLEX_NAME_VALUE = "external_model_complex_name"
    private val MODEL_FINALIZING_TYPE_VALUE = "external_model_finalizing_type"
    private val MODEL_FINALIZING_NAME_VALUE = "external_model_finalizing_name"

    private val ANTHROPIC_RATE_LIMIT_INPUT_TOKENS_VALUE = "anthropic_rate_limit_input_tokens"
    private val ANTHROPIC_RATE_LIMIT_OUTPUT_TOKENS_VALUE = "anthropic_rate_limit_output_tokens"
    private val ANTHROPIC_RATE_LIMIT_WINDOW_SECONDS_VALUE = "anthropic_rate_limit_window_seconds"

    // API URL constants
    private val ANTHROPIC_API_URL_VALUE = "anthropic.api.url"
    private val ANTHROPIC_API_VERSION_VALUE = "anthropic.api.version"
    private val OPENAI_API_URL_VALUE = "openai.api.url"

    // System prompt constants
    private val SYSTEM_PROMPT_CODE_VALUE = "system.prompt.code"
    private val SYSTEM_PROMPT_EXPLANATION_VALUE = "system.prompt.explanation"
    private val SYSTEM_PROMPT_SUMMARY_VALUE = "system.prompt.summary"
    private val SYSTEM_PROMPT_GENERAL_VALUE = "system.prompt.general"

    // LLM model constants
    private val LLM_TEMPERATURE_VALUE = "llm.temperature"
    private val LLM_DEFAULT_MAX_TOKENS_VALUE = "llm.default.max.tokens"
    private val LLM_MODEL_CODE_VALUE = "llm.model.code"
    private val LLM_MODEL_EXPLANATION_VALUE = "llm.model.explanation"
    private val LLM_MODEL_SUMMARY_VALUE = "llm.model.summary"
    private val LLM_MODEL_GENERAL_VALUE = "llm.model.general"

    // OpenAI model constants
    private val OPENAI_MODEL_CODE_VALUE = "openai.model.code"
    private val OPENAI_MODEL_EXPLANATION_VALUE = "openai.model.explanation"
    private val OPENAI_MODEL_SUMMARY_VALUE = "openai.model.summary"
    private val OPENAI_MODEL_GENERAL_VALUE = "openai.model.general"

    // Anthropic model constants
    private val ANTHROPIC_MODEL_CODE_VALUE = "anthropic.model.code"
    private val ANTHROPIC_MODEL_EXPLANATION_VALUE = "anthropic.model.explanation"
    private val ANTHROPIC_MODEL_SUMMARY_VALUE = "anthropic.model.summary"
    private val ANTHROPIC_MODEL_GENERAL_VALUE = "anthropic.model.general"

    // Startup constants
    private val START_UP_MINIMIZE = "startup.minimize"

    // Map to store pending changes that haven't been saved to the database yet
    private val pendingChanges = mutableMapOf<String, String?>()

    // Flag to track if there are any pending changes
    private var hasPendingChanges = false

    // Properties with direct database access
    var lmStudioUrl: String
        get() = runBlocking { getStringValue(LLM_STUDIO_URL, "http://localhost:1234") }
        set(value) = runBlocking { saveValue(LLM_STUDIO_URL, value, SettingType.STRING) }

    var ollamaUrl: String
        get() = runBlocking { getStringValue(OLLAMA_URL, "http://localhost:11434") }
        set(value) = runBlocking { saveValue(OLLAMA_URL, value, SettingType.STRING) }

    var embeddingModelName: String
        get() = runBlocking {
            resolveForUsageFromConfig("embedding")?.model
                ?: error("No 'embedding' model configured in application.yml (models)")
        }
        set(value) = runBlocking { saveValue(EMBEDDING_MODEL_NAME_VALUE, value, SettingType.STRING) }

    var embeddingModelType: ModelProvider
        get() =
            runBlocking {
                resolveForUsageFromConfig("embedding")?.provider
                    ?: error("No 'embedding' provider configured in application.yml (models)")
            }
        set(value) = runBlocking {
            // Save new provider key and legacy key for compatibility
            saveValue(EMBEDDING_PROVIDER_NAME_VALUE, value.name, SettingType.STRING)
            saveValue(EMBEDDING_TYPE_NAME_VALUE, value.name, SettingType.STRING)
        }

    var anthropicApiKey: String
        get() = runBlocking { getStringValue(ANTHROPIC_API_KEY_VALUE, "") }
        set(value) = runBlocking { saveValue(ANTHROPIC_API_KEY_VALUE, value, SettingType.STRING) }

    var lmStudioEnabled: Boolean
        get() = runBlocking { getBooleanValue(USE_LM_STUDIO, false) }
        set(value) = runBlocking { saveBooleanSetting(USE_LM_STUDIO, value) }

    var ollamaEnabled: Boolean
        get() = runBlocking { getBooleanValue(USE_OLLAMA, false) }
        set(value) = runBlocking { saveBooleanSetting(USE_OLLAMA, value) }

    var anthropicRateLimitInputTokens: Int
        get() = runBlocking { getIntValue(ANTHROPIC_RATE_LIMIT_INPUT_TOKENS_VALUE, 40000) }
        set(value) = runBlocking { saveIntSetting(ANTHROPIC_RATE_LIMIT_INPUT_TOKENS_VALUE, value) }

    var anthropicRateLimitOutputTokens: Int
        get() = runBlocking { getIntValue(ANTHROPIC_RATE_LIMIT_OUTPUT_TOKENS_VALUE, 8000) }
        set(value) = runBlocking { saveIntSetting(ANTHROPIC_RATE_LIMIT_OUTPUT_TOKENS_VALUE, value) }

    var anthropicRateLimitWindowSeconds: Int
        get() = runBlocking { getIntValue(ANTHROPIC_RATE_LIMIT_WINDOW_SECONDS_VALUE, 60) }
        set(value) = runBlocking { saveIntSetting(ANTHROPIC_RATE_LIMIT_WINDOW_SECONDS_VALUE, value) }

    var anthropicApiUrl: String
        get() = runBlocking { getStringValue(ANTHROPIC_API_URL_VALUE, "https://api.anthropic.com") }
        set(value) = runBlocking { saveValue(ANTHROPIC_API_URL_VALUE, value, SettingType.STRING) }

    var anthropicApiVersion: String
        get() = runBlocking { getStringValue(ANTHROPIC_API_VERSION_VALUE, "2023-06-01") }
        set(value) = runBlocking { saveValue(ANTHROPIC_API_VERSION_VALUE, value, SettingType.STRING) }

    var openaiApiUrl: String
        get() = runBlocking { getStringValue(OPENAI_API_URL_VALUE, "https://api.openai.com") }
        set(value) = runBlocking { saveValue(OPENAI_API_URL_VALUE, value, SettingType.STRING) }

    // LM Studio specific embedding and translation defaults
    var lmStudioEmbeddingTextModelName: String
        get() = runBlocking { getStringValue(EMBEDDING_TEXT_MODEL_NAME_VALUE, "text-embedding-nomic-embed-text-v2-moe") }
        set(value) = runBlocking { saveValue(EMBEDDING_TEXT_MODEL_NAME_VALUE, value, SettingType.STRING) }

    var lmStudioEmbeddingTextMaxTokens: Int
        get() = runBlocking { getIntValue(EMBEDDING_TEXT_MAX_TOKENS_VALUE, 512) }
        set(value) = runBlocking { saveIntSetting(EMBEDDING_TEXT_MAX_TOKENS_VALUE, value) }

    var lmStudioEmbeddingCodeModelName: String
        get() = runBlocking { getStringValue(EMBEDDING_CODE_MODEL_NAME_VALUE, "nomic-embed-code") }
        set(value) = runBlocking { saveValue(EMBEDDING_CODE_MODEL_NAME_VALUE, value, SettingType.STRING) }

    var lmStudioEmbeddingCodeMaxTokens: Int
        get() = runBlocking { getIntValue(EMBEDDING_CODE_MAX_TOKENS_VALUE, 4096) }
        set(value) = runBlocking { saveIntSetting(EMBEDDING_CODE_MAX_TOKENS_VALUE, value) }

    var lmStudioTranslationQuickModelName: String
        get() = runBlocking { getStringValue(TRANSLATION_QUICK_MODEL_NAME_VALUE, "deepseek-coder-v2-lite-instruct") }
        set(value) = runBlocking { saveValue(TRANSLATION_QUICK_MODEL_NAME_VALUE, value, SettingType.STRING) }

    var lmStudioTranslationQuickMaxTokens: Int
        get() = runBlocking { getIntValue(TRANSLATION_QUICK_MAX_TOKENS_VALUE, 4096) }
        set(value) = runBlocking { saveIntSetting(TRANSLATION_QUICK_MAX_TOKENS_VALUE, value) }

    var lmStudioTranslationQuickMaxTokensExceptional: Int
        get() = runBlocking { getIntValue(TRANSLATION_QUICK_MAX_TOKENS_EXCEPTIONAL_VALUE, 9196) }
        set(value) = runBlocking { saveIntSetting(TRANSLATION_QUICK_MAX_TOKENS_EXCEPTIONAL_VALUE, value) }

    // Ollama defaults (20 GB RAM friendly)
    var ollamaTextEmbeddingModelName: String
        get() = runBlocking { getStringValue(OLLAMA_TEXT_EMBED_MODEL_NAME_VALUE, "nomic-embed-text") }
        set(value) = runBlocking { saveValue(OLLAMA_TEXT_EMBED_MODEL_NAME_VALUE, value, SettingType.STRING) }

    var ollamaTextEmbeddingMaxTokens: Int
        get() = runBlocking { getIntValue(OLLAMA_TEXT_EMBED_MAX_TOKENS_VALUE, 512) }
        set(value) = runBlocking { saveIntSetting(OLLAMA_TEXT_EMBED_MAX_TOKENS_VALUE, value) }

    var ollamaCodeEmbeddingModelName: String
        get() = runBlocking { getStringValue(OLLAMA_CODE_EMBED_MODEL_NAME_VALUE, "nomic-embed-code") }
        set(value) = runBlocking { saveValue(OLLAMA_CODE_EMBED_MODEL_NAME_VALUE, value, SettingType.STRING) }

    var ollamaCodeEmbeddingMaxTokens: Int
        get() = runBlocking { getIntValue(OLLAMA_CODE_EMBED_MAX_TOKENS_VALUE, 4096) }
        set(value) = runBlocking { saveIntSetting(OLLAMA_CODE_EMBED_MAX_TOKENS_VALUE, value) }

    var ollamaTranslationModelName: String
        get() = runBlocking { getStringValue(OLLAMA_TRANSLATION_MODEL_NAME_VALUE, "llama3.1:8b-instruct") }
        set(value) = runBlocking { saveValue(OLLAMA_TRANSLATION_MODEL_NAME_VALUE, value, SettingType.STRING) }

    var ollamaTranslationMaxTokens: Int
        get() = runBlocking { getIntValue(OLLAMA_TRANSLATION_MAX_TOKENS_VALUE, 4096) }
        set(value) = runBlocking { saveIntSetting(OLLAMA_TRANSLATION_MAX_TOKENS_VALUE, value) }

    // Unified embedding max tokens (triplet: model, provider, max tokens)
    var embeddingMaxTokens: Int
        get() = runBlocking { getIntValue(EMBEDDING_MAX_TOKENS_VALUE, 4096) }
        set(value) = runBlocking { saveIntSetting(EMBEDDING_MAX_TOKENS_VALUE, value) }

    var llmTemperature: Float
        get() = runBlocking { getStringValue(LLM_TEMPERATURE_VALUE, "0.7").toFloatOrNull() ?: 0.7f }
        set(value) = runBlocking { saveValue(LLM_TEMPERATURE_VALUE, value.toString(), SettingType.STRING) }

    var llmDefaultMaxTokens: Int
        get() = runBlocking { getIntValue(LLM_DEFAULT_MAX_TOKENS_VALUE, 2048) }
        set(value) = runBlocking { saveIntSetting(LLM_DEFAULT_MAX_TOKENS_VALUE, value) }

    var llmModelCode: String
        get() = runBlocking { getStringValue(LLM_MODEL_CODE_VALUE, "gpt-3.5-turbo") }
        set(value) = runBlocking { saveValue(LLM_MODEL_CODE_VALUE, value, SettingType.STRING) }

    var llmModelExplanation: String
        get() = runBlocking { getStringValue(LLM_MODEL_EXPLANATION_VALUE, "gpt-3.5-turbo") }
        set(value) = runBlocking { saveValue(LLM_MODEL_EXPLANATION_VALUE, value, SettingType.STRING) }

    var llmModelSummary: String
        get() = runBlocking { getStringValue(LLM_MODEL_SUMMARY_VALUE, "gpt-3.5-turbo") }
        set(value) = runBlocking { saveValue(LLM_MODEL_SUMMARY_VALUE, value, SettingType.STRING) }

    var llmModelGeneral: String
        get() = runBlocking { getStringValue(LLM_MODEL_GENERAL_VALUE, "gpt-3.5-turbo") }
        set(value) = runBlocking { saveValue(LLM_MODEL_GENERAL_VALUE, value, SettingType.STRING) }

    var openaiModelCode: String
        get() = runBlocking { getStringValue(OPENAI_MODEL_CODE_VALUE, "gpt-3.5-turbo") }
        set(value) = runBlocking { saveValue(OPENAI_MODEL_CODE_VALUE, value, SettingType.STRING) }

    var openaiModelExplanation: String
        get() = runBlocking { getStringValue(OPENAI_MODEL_EXPLANATION_VALUE, "gpt-3.5-turbo") }
        set(value) = runBlocking { saveValue(OPENAI_MODEL_EXPLANATION_VALUE, value, SettingType.STRING) }

    var openaiModelSummary: String
        get() = runBlocking { getStringValue(OPENAI_MODEL_SUMMARY_VALUE, "gpt-3.5-turbo") }
        set(value) = runBlocking { saveValue(OPENAI_MODEL_SUMMARY_VALUE, value, SettingType.STRING) }

    var openaiModelGeneral: String
        get() = runBlocking { getStringValue(OPENAI_MODEL_GENERAL_VALUE, "gpt-3.5-turbo") }
        set(value) = runBlocking { saveValue(OPENAI_MODEL_GENERAL_VALUE, value, SettingType.STRING) }

    var anthropicModelCode: String
        get() = runBlocking { getStringValue(ANTHROPIC_MODEL_CODE_VALUE, "claude-3-haiku-20240307") }
        set(value) = runBlocking { saveValue(ANTHROPIC_MODEL_CODE_VALUE, value, SettingType.STRING) }

    var anthropicModelExplanation: String
        get() = runBlocking { getStringValue(ANTHROPIC_MODEL_EXPLANATION_VALUE, "claude-3-haiku-20240307") }
        set(value) = runBlocking { saveValue(ANTHROPIC_MODEL_EXPLANATION_VALUE, value, SettingType.STRING) }

    var anthropicModelSummary: String
        get() = runBlocking { getStringValue(ANTHROPIC_MODEL_SUMMARY_VALUE, "claude-3-haiku-20240307") }
        set(value) = runBlocking { saveValue(ANTHROPIC_MODEL_SUMMARY_VALUE, value, SettingType.STRING) }

    var anthropicModelGeneral: String
        get() = runBlocking { getStringValue(ANTHROPIC_MODEL_GENERAL_VALUE, "claude-3-haiku-20240307") }
        set(value) = runBlocking { saveValue(ANTHROPIC_MODEL_GENERAL_VALUE, value, SettingType.STRING) }

    /**
     * Gets the setting value by key
     * Returns the current value from the database, ignoring any pending changes
     */
    private suspend fun getSettingValue(key: String): String? = settingRepository.findByKey(key)?.value

    /**
     * Gets the pending value for a setting, if any
     * @return The pending value, or null if there is no pending change
     */
    private fun getPendingValue(key: String): String? = pendingChanges[key]

    /**
     * Checks if a setting has a pending change
     * @return True if the setting has a pending change, false otherwise
     */
    private fun hasPendingChange(key: String): Boolean = pendingChanges.containsKey(key)

    /**
     * Gets the effective value for a setting (pending if available, otherwise current)
     * @return The effective value, or null if the setting doesn't exist and has no pending value
     */
    suspend fun getEffectiveValue(key: String): String? = if (hasPendingChange(key)) getPendingValue(key) else getSettingValue(key)

    /**
     * Gets the setting value as a boolean
     * Returns the effective value (pending if available, otherwise current)
     */
    private suspend fun getBooleanValue(
        key: String,
        defaultValue: Boolean = false,
    ): Boolean {
        val value = getEffectiveValue(key) ?: return defaultValue
        return value.equals("true", ignoreCase = true)
    }

    /**
     * Gets the setting value as a string
     * Returns the effective value (pending if available, otherwise current)
     */
    suspend fun getStringValue(
        key: String,
        defaultValue: String = "none",
    ): String = getEffectiveValue(key) ?: defaultValue

    /**
     * Gets the setting value as an integer
     * Returns the effective value (pending if available, otherwise current)
     */
    private suspend fun getIntValue(
        key: String,
        defaultValue: Int = 0,
    ): Int {
        val value = getEffectiveValue(key) ?: return defaultValue
        return try {
            value.toInt()
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }

    /**
     * Saves a value directly to the database (bypasses pending changes)
     */
    suspend fun saveValue(
        key: String,
        value: String,
        type: SettingType = SettingType.STRING,
    ) {
        val existingSetting = settingRepository.findByKey(key)
        val setting =
            existingSetting?.copy(
                value = value,
                type = type,
                updatedAt = Instant.now(),
            )
                ?: SettingDocument(
                    key = key,
                    value = value,
                    type = type,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                )
        settingRepository.save(setting)
        
        // In batch mode, collect changes instead of publishing immediately
        if (batchMode) {
            batchChangedSettings.add(key)
        } else {
            eventPublisher.publishEvent(SettingsChangeEvent(this))
        }
    }

    /**
     * Saves a boolean setting directly to the database
     */
    private suspend fun saveBooleanSetting(
        key: String,
        value: Boolean,
    ) {
        saveValue(key, value.toString(), SettingType.BOOLEAN)
    }

    /**
     * Saves an integer setting directly to the database
     */
    private suspend fun saveIntSetting(
        key: String,
        value: Int,
    ) {
        saveValue(key, value.toString(), SettingType.INTEGER)
    }

    /**
     * Determines the setting type based on the value
     */
    private fun determineSettingType(value: String?): SettingType {
        if (value == null) return SettingType.STRING

        return when {
            value.equals("true", ignoreCase = true) || value.equals("false", ignoreCase = true) -> SettingType.BOOLEAN
            value.toIntOrNull() != null -> SettingType.INTEGER
            value.toDoubleOrNull() != null -> SettingType.DOUBLE
            else -> SettingType.STRING
        }
    }

    // Model provider methods
    suspend fun getEmbeddingModel(): Pair<ModelProvider, String> = Pair(embeddingModelType, embeddingModelName)

    suspend fun getModelSimple(): Pair<ModelProvider, String> = Pair(modelSimpleType, modelSimpleName)

    suspend fun getModelComplex(): Pair<ModelProvider, String> = Pair(modelComplexType, modelComplexName)

    suspend fun getModelFinalizing(): Pair<ModelProvider, String> = Pair(modelFinalizingType, modelFinalizingName)

    suspend fun setEmbeddingModel(
        type: ModelProvider,
        name: String,
    ) {
        embeddingModelType = type
        embeddingModelName = name
    }

    // Model properties
    var modelSimpleType: ModelProvider
        get() =
            runBlocking {
                resolveForUsageFromConfig("simple")?.provider
                    ?: error("No 'simple' provider configured in application.yml (models)")
            }
        set(value) = runBlocking { saveValue(MODEL_SIMPLE_TYPE_VALUE, value.name, SettingType.STRING) }

    var modelSimpleName: String
        get() = runBlocking { resolveForUsageFromConfig("simple")?.model ?: error("No 'simple' model configured in application.yml (models)") }
        set(value) = runBlocking { saveValue(MODEL_SIMPLE_NAME_VALUE, value, SettingType.STRING) }

    var modelComplexType: ModelProvider
        get() =
            runBlocking {
                resolveForUsageFromConfig("complex")?.provider
                    ?: error("No 'complex' provider configured in application.yml (models)")
            }
        set(value) = runBlocking { saveValue(MODEL_COMPLEX_TYPE_VALUE, value.name, SettingType.STRING) }

    var modelComplexName: String
        get() = runBlocking { resolveForUsageFromConfig("complex")?.model ?: error("No 'complex' model configured in application.yml (models)") }
        set(value) = runBlocking { saveValue(MODEL_COMPLEX_NAME_VALUE, value, SettingType.STRING) }

    var modelFinalizingType: ModelProvider
        get() =
            runBlocking {
                resolveForUsageFromConfig("finalizing")?.provider
                    ?: error("No 'finalizing' provider configured in application.yml (models)")
            }
        set(value) = runBlocking { saveValue(MODEL_FINALIZING_TYPE_VALUE, value.name, SettingType.STRING) }

    var modelFinalizingName: String
        get() = runBlocking { resolveForUsageFromConfig("finalizing")?.model ?: error("No 'finalizing' model configured in application.yml (models)") }
        set(value) = runBlocking { saveValue(MODEL_FINALIZING_NAME_VALUE, value, SettingType.STRING) }

    // Convenience methods for setting both type and name
    suspend fun setModelSimple(
        type: ModelProvider,
        name: String,
    ) {
        modelSimpleType = type
        modelSimpleName = name
    }

    suspend fun setModelComplex(
        type: ModelProvider,
        name: String,
    ) {
        modelComplexType = type
        modelComplexName = name
    }

    suspend fun setModelFinalizing(
        type: ModelProvider,
        name: String,
    ) {
        modelFinalizingType = type
        modelFinalizingName = name
    }

    // Batch operation methods
    /**
     * Start batch mode to collect multiple setting changes without publishing individual events
     */
    fun startBatch(changeType: SettingsChangeEvent.ChangeType = SettingsChangeEvent.ChangeType.GENERAL) {
        batchMode = true
        batchChangedSettings.clear()
        batchChangeType = changeType
    }

    /**
     * End batch mode and publish a single consolidated event with all changes
     */
    fun endBatch() {
        if (batchMode && batchChangedSettings.isNotEmpty()) {
            eventPublisher.publishEvent(
                SettingsChangeEvent(
                    source = this,
                    changedSettings = batchChangedSettings.toSet(),
                    changeType = batchChangeType
                )
            )
        }
        batchMode = false
        batchChangedSettings.clear()
        batchChangeType = SettingsChangeEvent.ChangeType.GENERAL
    }

    // Blocking wrapper methods for compatibility
    fun saveValueBlocking(
        key: String,
        value: String,
        type: SettingType = SettingType.STRING,
    ) = runBlocking {
        saveValue(key, value, type)
    }

}
