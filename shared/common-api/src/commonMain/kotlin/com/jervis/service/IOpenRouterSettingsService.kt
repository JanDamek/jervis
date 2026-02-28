package com.jervis.service

import com.jervis.dto.openrouter.OpenRouterCatalogModelDto
import com.jervis.dto.openrouter.OpenRouterSettingsDto
import com.jervis.dto.openrouter.OpenRouterSettingsUpdateDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IOpenRouterSettingsService {
    /** Get current OpenRouter settings (returns defaults if never saved). */
    suspend fun getSettings(): OpenRouterSettingsDto

    /** Update OpenRouter settings. Only non-null fields are applied. */
    suspend fun updateSettings(request: OpenRouterSettingsUpdateDto): OpenRouterSettingsDto

    /** Fetch available models from OpenRouter API catalog (filtered by current settings). */
    suspend fun fetchCatalogModels(): List<OpenRouterCatalogModelDto>

    /** Test API key connectivity — returns true if the key is valid. */
    suspend fun testConnection(): Boolean
}
