package com.jervis.service

import com.jervis.entity.EmbeddingModelType
import com.jervis.entity.LlmModelType
import com.jervis.entity.Setting
import com.jervis.entity.SettingType
import com.jervis.entity.fromString
import com.jervis.module.llm.SettingsChangeEvent
import com.jervis.repository.SettingRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class SettingService(
    private val settingRepository: SettingRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    // Private constants - pouze SettingService může používat
    private val OLLAMA_URL: String = "ollama_url"
    private val LLM_STUDIO_URL: String = "lm_studio_url"
    private val USE_OLLAMA: String = "use_ollama"
    private val USE_LM_STUDIO: String = "use_lm_studio"
    private val ANTHROPIC_API_KEY_VALUE = "anthropic_api_key"
    private val OPENAI_API_KEY_VALUE = "openai_api_key"

    // Constants for external model settings
    private val EMBEDDING_MODEL_NAME_VALUE = "embedding_model"
    private val EMBEDDING_TYPE_NAME_VALUE = "embedding_type"

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

    // Startup constants
    private val START_UP_MINIMIZE = "startup.minimize"

    // Map to store pending changes that haven't been saved to the database yet
    private val pendingChanges = mutableMapOf<String, String?>()

    // Flag to track if there are any pending changes
    private var hasPendingChanges = false

    /**
     * Gets the setting value by key
     * Returns the current value from the database, ignoring any pending changes
     */
    fun getSettingValue(key: String): String? = settingRepository.findByKey(key).map { it.value }.orElse(null)

    /**
     * Gets the pending value for a setting, if any
     * @return The pending value, or null if there is no pending change
     */
    fun getPendingValue(key: String): String? = pendingChanges[key]

    /**
     * Checks if a setting has a pending change
     * @return True if the setting has a pending change, false otherwise
     */
    fun hasPendingChange(key: String): Boolean = pendingChanges.containsKey(key)

    /**
     * Gets the effective value for a setting (pending if available, otherwise current)
     * @return The effective value, or null if the setting doesn't exist and has no pending value
     */
    fun getEffectiveValue(key: String): String? = if (hasPendingChange(key)) getPendingValue(key) else getSettingValue(key)

    /**
     * Gets the setting value as a boolean
     * Returns the effective value (pending if available, otherwise current)
     */
    private fun getBooleanValue(
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
    private fun getStringValue(
        key: String,
        defaultValue: String = "none",
    ): String {
        return getEffectiveValue(key) ?: return defaultValue
    }

    /**
     * Gets the setting value as an integer
     * Returns the effective value (pending if available, otherwise current)
     */
    private fun getIntValue(
        key: String,
        defaultValue: Int = 0,
    ): Int {
        val value = getEffectiveValue(key) ?: return defaultValue
        return try {
            value.toInt()
        } catch (_: NumberFormatException) {
            defaultValue
        }
    }

    /**
     * Prepares a setting value for saving
     * The value is stored in the pending changes map but not saved to the database
     * Use saveChanges() to commit the changes to the database
     */
    private fun prepareValue(
        key: String,
        value: String,
        type: SettingType = SettingType.STRING,
    ) {
        // Store the value in the pending changes map
        pendingChanges[key] = value
        hasPendingChanges = true
    }

    /**
     * Prepares a boolean setting value for saving
     * The value is stored in the pending changes map but not saved to the database
     * Use saveChanges() to commit the changes to the database
     */
    fun prepareBooleanSetting(
        key: String,
        value: Boolean,
    ) {
        prepareValue(key, value.toString(), SettingType.BOOLEAN)
    }

    /**
     * Prepares an integer setting value for saving
     * The value is stored in the pending changes map but not saved to the database
     * Use saveChanges() to commit the changes to the database
     */
    fun prepareIntSetting(
        key: String,
        value: Int,
    ) {
        prepareValue(key, value.toString(), SettingType.INTEGER)
    }

    /**
     * Saves a setting value directly to the database
     * This bypasses the pending changes mechanism and should be used with caution
     */
    fun saveValue(
        key: String,
        value: String,
        type: SettingType = SettingType.STRING,
    ) {
        val setting =
            settingRepository.findByKey(key).orElse(
                Setting(key = key, type = type),
            )

        // Check if the value has changed
        val valueChanged = setting.value != value

        setting.value = value
        setting.updatedAt = LocalDateTime.now()

        settingRepository.save(setting)

        // Publish event if the value has changed
        if (valueChanged) {
            eventPublisher.publishEvent(SettingsChangeEvent(this))
        }
    }

    /**
     * Saves a boolean setting value directly to the database
     * This bypasses the pending changes mechanism and should be used with caution
     */
    private fun saveBooleanSetting(
        key: String,
        value: Boolean,
    ) {
        saveValue(key, value.toString(), SettingType.BOOLEAN)
    }

    /**
     * Saves an integer setting value directly to the database
     * This bypasses the pending changes mechanism and should be used with caution
     */
    private fun saveIntSetting(
        key: String,
        value: Int,
    ) {
        saveValue(key, value.toString(), SettingType.INTEGER)
    }

    /**
     * Saves all pending changes to the database
     * @return True if changes were saved, false if there were no pending changes
     */
    fun saveChanges(): Boolean {
        if (!hasPendingChanges) {
            return false
        }

        var anyValueChanged = false

        // Save each pending change to the database
        pendingChanges.forEach { (key, value) ->
            val setting =
                settingRepository.findByKey(key).orElse(
                    Setting(key = key, type = determineSettingType(value)),
                )

            // Check if the value has changed
            val valueChanged = setting.value != value
            anyValueChanged = anyValueChanged || valueChanged

            setting.value = value
            setting.updatedAt = LocalDateTime.now()

            settingRepository.save(setting)
        }

        // Clear the pending changes
        pendingChanges.clear()
        hasPendingChanges = false

        // Publish event if any value has changed
        if (anyValueChanged) {
            eventPublisher.publishEvent(SettingsChangeEvent(this))
        }

        return true
    }

    /**
     * Cancels all pending changes
     * @return True if changes were canceled, false if there were no pending changes
     */
    fun cancelChanges(): Boolean {
        if (!hasPendingChanges) {
            return false
        }

        // Clear the pending changes
        pendingChanges.clear()
        hasPendingChanges = false

        return true
    }

    /**
     * Determines the setting type based on the value
     * @param value The value to determine the type for
     * @return The determined setting type
     */
    private fun determineSettingType(value: String?): SettingType {
        if (value == null) {
            return SettingType.STRING
        }

        return when {
            value.equals("true", ignoreCase = true) || value.equals("false", ignoreCase = true) -> SettingType.BOOLEAN
            value.toIntOrNull() != null -> SettingType.INTEGER
            value.toDoubleOrNull() != null -> SettingType.DOUBLE
            else -> SettingType.STRING
        }
    }

    // Embedding model methods
    fun getEmbeddingModelTypeEnum(): EmbeddingModelType =
        getStringValue(EMBEDDING_TYPE_NAME_VALUE, EmbeddingModelType.INTERNAL.value).fromString()

    fun getEmbeddingModel(): Pair<EmbeddingModelType, String> {
        val type = getEmbeddingModelTypeEnum()
        val name = getEmbeddingModelName()
        return type to name
    }

    fun getEmbeddingModelName() = getStringValue(EMBEDDING_MODEL_NAME_VALUE, "intfloat/multilingual-e5-large")

    fun saveEmbeddingModelTypeEnum(modelType: EmbeddingModelType) {
        saveValue(EMBEDDING_TYPE_NAME_VALUE, modelType.value)
    }

    fun setEmbedingModelName(modelName: String) {
        saveValue(EMBEDDING_MODEL_NAME_VALUE, modelName)
    }

    fun setEmbeddingModel(
        type: EmbeddingModelType,
        name: String,
    ) {
        saveEmbeddingModelTypeEnum(type)
        setEmbedingModelName(name)
    }

    // Simple model methods
    fun getModelSimple(): Pair<LlmModelType, String> {
        val typeString = getStringValue(MODEL_SIMPLE_TYPE_VALUE, LlmModelType.LM_STUDIO.value)
        val type =
            try {
                LlmModelType.valueOf(typeString)
            } catch (e: IllegalArgumentException) {
                LlmModelType.LM_STUDIO
            }
        val name = getStringValue(MODEL_SIMPLE_NAME_VALUE, "")
        return type to name
    }

    fun setModelSimple(
        type: LlmModelType,
        name: String,
    ) {
        saveValue(MODEL_SIMPLE_TYPE_VALUE, type.value)
        saveValue(MODEL_SIMPLE_NAME_VALUE, name)
    }

    fun setModelSimpleName(name: String) {
        saveValue(MODEL_SIMPLE_NAME_VALUE, name)
    }

    // Complex model methods
    fun getModelComplex(): Pair<LlmModelType, String> {
        val typeString = getStringValue(MODEL_COMPLEX_TYPE_VALUE, LlmModelType.LM_STUDIO.value)
        val type =
            try {
                LlmModelType.valueOf(typeString)
            } catch (e: IllegalArgumentException) {
                LlmModelType.LM_STUDIO
            }
        val name = getStringValue(MODEL_COMPLEX_NAME_VALUE, "")
        return type to name
    }

    fun setModelComplex(
        type: LlmModelType,
        name: String,
    ) {
        saveValue(MODEL_COMPLEX_TYPE_VALUE, type.value)
        saveValue(MODEL_COMPLEX_NAME_VALUE, name)
    }

    fun setModelComplexName(name: String) {
        saveValue(MODEL_COMPLEX_NAME_VALUE, name)
    }

    // Finalizing model methods (nová funkcionalita)
    fun getModelFinalizing(): Pair<LlmModelType, String> {
        val typeString = getStringValue(MODEL_FINALIZING_TYPE_VALUE, LlmModelType.LM_STUDIO.value)
        val type =
            try {
                LlmModelType.valueOf(typeString)
            } catch (e: IllegalArgumentException) {
                LlmModelType.LM_STUDIO
            }
        val name = getStringValue(MODEL_FINALIZING_NAME_VALUE, "")
        return type to name
    }

    fun setModelFinalizing(
        type: LlmModelType,
        name: String,
    ) {
        saveValue(MODEL_FINALIZING_TYPE_VALUE, type.value)
        saveValue(MODEL_FINALIZING_NAME_VALUE, name)
    }

    fun setModelFinalizingName(name: String) {
        saveValue(MODEL_FINALIZING_NAME_VALUE, name)
    }

    // Local providers methods

    /**
     * Checks if LM Studio is enabled
     * @return True if LM Studio is enabled, false otherwise
     */
    fun isLmStudioEnabled(): Boolean = getBooleanValue(USE_LM_STUDIO, true)

    /**
     * Checks if Ollama is enabled
     * @return True if Ollama is enabled, false otherwise
     */
    fun isOllamaEnabled(): Boolean = getBooleanValue(USE_OLLAMA, true)

    /**
     * Gets the LM Studio URL
     * @return The LM Studio URL
     */
    fun getLmStudioUrl(): String = getStringValue(LLM_STUDIO_URL, "http://localhost:1234/v1/chat/completions")

    /**
     * Gets the Ollama URL
     * @return The Ollama URL
     */
    fun getOllamaUrl(): String = getStringValue(OLLAMA_URL, "http://localhost:11434")

    fun setOllamaEnabled(selected: Boolean) {
        saveBooleanSetting(USE_OLLAMA, selected)
    }

    fun setOllamaUrl(text: String) {
        saveValue(OLLAMA_URL, text)
    }

    fun setLmStuiodEnabled(selected: Boolean) {
        saveBooleanSetting(USE_LM_STUDIO, selected)
    }

    fun setLmStudioUrl(text: String) {
        saveValue(LLM_STUDIO_URL, text)
    }

    // API keys methods
    fun getOpenaiApiKey(): String = getStringValue(OPENAI_API_KEY_VALUE, "")

    fun getAnthropicApiKey(): String = getStringValue(ANTHROPIC_API_KEY_VALUE, "")

    fun setAnthropicApiKey(newAnthropicApiKey: String) {
        saveValue(ANTHROPIC_API_KEY_VALUE, newAnthropicApiKey)
    }

    fun setOpenAiApiKey(newOpenAiApiKey: String) {
        saveValue(OPENAI_API_KEY_VALUE, newOpenAiApiKey)
    }

    // Rate limiting methods
    fun setAnthropicRateLimitInputTokens(inputRateLimitTokens: Int) {
        saveIntSetting(ANTHROPIC_RATE_LIMIT_INPUT_TOKENS_VALUE, inputRateLimitTokens)
    }

    fun setAnthropicRateLimitOutputTokens(outputRateLimitTokens: Int) {
        saveIntSetting(ANTHROPIC_RATE_LIMIT_OUTPUT_TOKENS_VALUE, outputRateLimitTokens)
    }

    fun setAnthropicRateLimitWindowSeconds(rateLimitWindow: Int) {
        saveIntSetting(ANTHROPIC_RATE_LIMIT_WINDOW_SECONDS_VALUE, rateLimitWindow)
    }

    fun getAnthropicRateLimitInputTokens(): Int = getIntValue(ANTHROPIC_RATE_LIMIT_INPUT_TOKENS_VALUE, 20000)

    fun getAnthropicRateLimitOutputTokens(): Int = getIntValue(ANTHROPIC_RATE_LIMIT_OUTPUT_TOKENS_VALUE, 4000)

    fun getAnthropicRateLimitWindowSeconds(): Int = getIntValue(ANTHROPIC_RATE_LIMIT_WINDOW_SECONDS_VALUE, 60)

    // API URL methods
    fun getAnthropicApiUrlValue(): String = getStringValue(ANTHROPIC_API_URL_VALUE, "https://api.anthropic.com/v1/messages")

    fun getAnthropicApiVersionValue(): String = getStringValue(ANTHROPIC_API_VERSION_VALUE, "2023-06-01")

    fun getOpenaiApiUrlValue(): String = getStringValue(OPENAI_API_URL_VALUE, "https://api.openai.com/v1/chat/completions")

    // System prompt methods
    fun getSystemPromptCodeValue(): String = getStringValue(SYSTEM_PROMPT_CODE_VALUE, "You are a helpful assistant.")

    fun getSystemPromptExplanationValue(): String = getStringValue(SYSTEM_PROMPT_EXPLANATION_VALUE, "You are a helpful assistant.")

    fun getSystemPromptSummaryValue(): String = getStringValue(SYSTEM_PROMPT_SUMMARY_VALUE, "You are a helpful assistant.")

    fun getSystemPromptGeneralValue(): String = getStringValue(SYSTEM_PROMPT_GENERAL_VALUE, "You are a helpful assistant.")

    // LLM model methods
    fun getLlmTemperature(): Float = getStringValue(LLM_TEMPERATURE_VALUE, "0.7").toFloatOrNull() ?: 0.7f

    fun getLlmDefaultMaxTokensValue(): Int = getIntValue(LLM_DEFAULT_MAX_TOKENS_VALUE, 1024)

    // Model value methods
    fun getLlmModelCodeValueBlocking(): String = getStringValue(LLM_MODEL_CODE_VALUE, "claude-3-haiku-20240307")

    fun getLlmModelExplanationValueBlocking(): String = getStringValue(LLM_MODEL_EXPLANATION_VALUE, "claude-3-haiku-20240307")

    fun getLlmModelSummaryValueBlocking(): String = getStringValue(LLM_MODEL_SUMMARY_VALUE, "claude-3-haiku-20240307")

    fun getLlmModelGeneralValueBlocking(): String = getStringValue(LLM_MODEL_GENERAL_VALUE, "claude-3-haiku-20240307")

    fun getOpenaiModelCodeValueBlocking(): String = getStringValue(OPENAI_MODEL_CODE_VALUE, "gpt-3.5-turbo")

    fun getOpenaiModelExplanationValueBlocking(): String = getStringValue(OPENAI_MODEL_EXPLANATION_VALUE, "gpt-3.5-turbo")

    fun getOpenaiModelSummaryValueBlocking(): String = getStringValue(OPENAI_MODEL_SUMMARY_VALUE, "gpt-3.5-turbo")

    fun getOpenaiModelGeneralValueBlocking(): String = getStringValue(OPENAI_MODEL_GENERAL_VALUE, "gpt-3.5-turbo")

    // String value blocking methods
    fun getStringValueBlocking(
        key: String,
        defaultValue: String = "none",
    ): String = getStringValue(key, defaultValue)

    // Save value blocking methods
    fun saveValueBlocking(
        key: String,
        value: String,
        type: SettingType = SettingType.STRING,
    ) {
        saveValue(key, value, type)
    }

    // Getter methods for setting keys
    fun getAnthropicApiUrl(): String = ANTHROPIC_API_URL_VALUE

    fun getAnthropicApiVersion(): String = ANTHROPIC_API_VERSION_VALUE

    fun getOpenaiApiUrl(): String = OPENAI_API_URL_VALUE

    // Int value blocking methods
    fun getIntValueBlocking(
        key: String,
        defaultValue: Int = 0,
    ): Int = getIntValue(key, defaultValue)

    fun getStartupMinimize() = getBooleanValue(START_UP_MINIMIZE, false)
}
