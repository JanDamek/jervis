package com.jervis.service

import com.jervis.entity.Setting
import com.jervis.entity.SettingType
import com.jervis.repository.SettingRepository
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class SettingService(
    private val settingRepository: SettingRepository,
) {
    /**
     * Získá hodnotu nastavení podle klíče
     */
    fun getSettingValue(key: String): String? = settingRepository.findByKey(key).map { it.value }.orElse(null)

    /**
     * Získá hodnotu nastavení jako boolean
     */
    fun getBooleanValue(
        key: String,
        defaultValue: Boolean = false,
    ): Boolean {
        val value = getSettingValue(key) ?: return defaultValue
        return value.equals("true", ignoreCase = true)
    }

    fun getStringValue(
        key: String,
        defaultValue: String = "none",
    ): String {
        return getSettingValue(key) ?: return defaultValue
    }

    /**
     * Získá hodnotu nastavení jako integer
     */
    fun getIntValue(
        key: String,
        defaultValue: Int = 0,
    ): Int {
        val value = getSettingValue(key) ?: return defaultValue
        return try {
            value.toInt()
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }

    /**
     * Uloží hodnotu nastavení
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

        setting.value = value
        setting.updatedAt = LocalDateTime.now()

        settingRepository.save(setting)
    }

    /**
     * Uloží hodnotu nastavení jako boolean
     */
    fun saveBooleanSetting(
        key: String,
        value: Boolean,
    ) {
        saveValue(key, value.toString(), SettingType.BOOLEAN)
    }

    /**
     * Uloží hodnotu nastavení jako integer
     */
    fun saveIntSetting(
        key: String,
        value: Int,
    ) {
        saveValue(key, value.toString(), SettingType.INTEGER)
    }

    companion object {
        const val QDRANT_URL: String = "qdrant_url"
        const val RAG_PORT: String = "rag_port"
        const val ANTHROPIC_API_KEY: String = "anthropic_api_key"
        const val OPENAI_API_KEY: String = "openai_api_key"

        // LLM Coordinator settings
        const val ANTHROPIC_API_URL: String = "anthropic_api_url"
        const val ANTHROPIC_API_VERSION: String = "anthropic_api_version"
        const val OPENAI_API_URL: String = "openai_api_url"
        const val LLM_DEFAULT_TEMPERATURE: String = "llm_default_temperature"
        const val LLM_DEFAULT_MAX_TOKENS: String = "llm_default_max_tokens"

        // Rate limiting settings
        const val ANTHROPIC_RATE_LIMIT_INPUT_TOKENS: String = "anthropic_rate_limit_input_tokens"
        const val ANTHROPIC_RATE_LIMIT_OUTPUT_TOKENS: String = "anthropic_rate_limit_output_tokens"
        const val ANTHROPIC_RATE_LIMIT_WINDOW_SECONDS: String = "anthropic_rate_limit_window_seconds"

        // System prompts for different query types
        const val SYSTEM_PROMPT_CODE: String = "system_prompt_code"
        const val SYSTEM_PROMPT_EXPLANATION: String = "system_prompt_explanation"
        const val SYSTEM_PROMPT_SUMMARY: String = "system_prompt_summary"
        const val SYSTEM_PROMPT_GENERAL: String = "system_prompt_general"

        // Default LLM models for different query types
        const val LLM_MODEL_CODE: String = "llm_model_code"
        const val LLM_MODEL_EXPLANATION: String = "llm_model_explanation"
        const val LLM_MODEL_SUMMARY: String = "llm_model_summary"
        const val LLM_MODEL_GENERAL: String = "llm_model_general"

        // OpenAI models for different query types
        const val OPENAI_MODEL_CODE: String = "openai_model_code"
        const val OPENAI_MODEL_EXPLANATION: String = "openai_model_explanation"
        const val OPENAI_MODEL_SUMMARY: String = "openai_model_summary"
        const val OPENAI_MODEL_GENERAL: String = "openai_model_general"

        // Fallback settings
        const val FALLBACK_TO_OPENAI_ON_RATE_LIMIT: String = "fallback_to_openai_on_rate_limit"
    }
}
