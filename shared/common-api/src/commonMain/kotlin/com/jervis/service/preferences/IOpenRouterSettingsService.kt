package com.jervis.service.preferences

import com.jervis.dto.openrouter.ModelErrorDto
import com.jervis.dto.openrouter.ModelStatsDto
import com.jervis.dto.openrouter.ModelTestResultDto
import com.jervis.dto.openrouter.OpenRouterCatalogModelDto
import com.jervis.dto.openrouter.OpenRouterFiltersDto
import com.jervis.dto.openrouter.OpenRouterSettingsDto
import com.jervis.dto.openrouter.OpenRouterSettingsUpdateDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IOpenRouterSettingsService {
    /** Get current OpenRouter settings (returns defaults if never saved). */
    suspend fun getSettings(): OpenRouterSettingsDto

    /** Update OpenRouter settings. Only non-null fields are applied. */
    suspend fun updateSettings(request: OpenRouterSettingsUpdateDto): OpenRouterSettingsDto

    /** Fetch available models from OpenRouter API catalog, filtered by provided filters (from UI). */
    suspend fun fetchCatalogModels(filters: OpenRouterFiltersDto = OpenRouterFiltersDto()): List<OpenRouterCatalogModelDto>

    /** Test API key connectivity — returns true if the key is valid. */
    suspend fun testConnection(): Boolean

    /** Get runtime model error state from the router (in-memory, not persisted). */
    suspend fun getModelErrors(): List<ModelErrorDto>

    /** Reset error state for a model in the router (re-enables it). Returns true if it was disabled. */
    suspend fun resetModelError(modelId: String): Boolean

    /** Test a specific model by sending a tiny completion request. Verifies model actually responds. */
    suspend fun testModel(modelId: String): ModelTestResultDto

    /** Get usage statistics for all models (call count, avg response time). */
    suspend fun getModelStats(): List<ModelStatsDto>
}
