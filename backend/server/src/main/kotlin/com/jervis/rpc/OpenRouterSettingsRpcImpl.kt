package com.jervis.rpc

import com.jervis.dto.openrouter.*
import com.jervis.entity.ModelQueue
import com.jervis.entity.OpenRouterFilters
import com.jervis.entity.OpenRouterModelEntry
import com.jervis.entity.OpenRouterSettingsDocument
import com.jervis.entity.QueueModelEntry
import com.jervis.repository.OpenRouterSettingsRepository
import com.jervis.service.IOpenRouterSettingsService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class OpenRouterSettingsRpcImpl(
    private val repository: OpenRouterSettingsRepository,
    @Value("\${endpoints.ollama-router.url:http://jervis-ollama-router:11430}")
    private val routerUrl: String = "http://jervis-ollama-router:11430",
) : IOpenRouterSettingsService {
    private val logger = KotlinLogging.logger {}

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

    override suspend fun getSettings(): OpenRouterSettingsDto {
        val doc = repository.findById(OpenRouterSettingsDocument.SINGLETON_ID)
            ?: OpenRouterSettingsDocument()
        return doc.toDto()
    }

    override suspend fun updateSettings(request: OpenRouterSettingsUpdateDto): OpenRouterSettingsDto {
        val existing = repository.findById(OpenRouterSettingsDocument.SINGLETON_ID)
            ?: OpenRouterSettingsDocument()

        val updated = existing.copy(
            apiKey = request.apiKey ?: existing.apiKey,
            apiBaseUrl = request.apiBaseUrl ?: existing.apiBaseUrl,
            enabled = request.enabled ?: existing.enabled,
            filters = request.filters?.toEntity() ?: existing.filters,
            models = request.models?.map { it.toEntity() } ?: existing.models,
            monthlyBudgetUsd = request.monthlyBudgetUsd ?: existing.monthlyBudgetUsd,
            fallbackStrategy = request.fallbackStrategy?.name ?: existing.fallbackStrategy,
            modelQueues = request.modelQueues?.map { it.toEntity() } ?: existing.modelQueues,
        )

        repository.save(updated)
        logger.info { "OpenRouter settings updated: enabled=${updated.enabled}, models=${updated.models.size}" }
        return updated.toDto()
    }

    override suspend fun fetchCatalogModels(filters: OpenRouterFiltersDto): List<OpenRouterCatalogModelDto> {
        val doc = repository.findById(OpenRouterSettingsDocument.SINGLETON_ID)
            ?: OpenRouterSettingsDocument()

        if (doc.apiKey.isBlank()) {
            logger.warn { "Cannot fetch catalog: API key not configured" }
            return emptyList()
        }

        return try {
            val baseUrl = doc.apiBaseUrl.trimEnd('/')
            val response: OpenRouterModelsResponse = httpClient.get("$baseUrl/models") {
                header("Authorization", "Bearer ${doc.apiKey}")
                header("HTTP-Referer", "https://jervis.app")
            }.body()

            val entityFilters = filters.toEntity()
            response.data
                .filter { model -> applyFilters(model, entityFilters) }
                .map { model ->
                    OpenRouterCatalogModelDto(
                        id = model.id,
                        name = model.name,
                        contextLength = model.contextLength ?: 0,
                        inputPricePerMillion = parsePrice(model.pricing?.prompt),
                        outputPricePerMillion = parsePrice(model.pricing?.completion),
                        supportsTools = model.supportsFunctionCalling(),
                        supportsStreaming = true,
                        provider = model.id.substringBefore("/", ""),
                    )
                }
                .sortedBy { it.inputPricePerMillion }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch OpenRouter catalog: ${e.message}" }
            emptyList()
        }
    }

    override suspend fun testConnection(): Boolean {
        val doc = repository.findById(OpenRouterSettingsDocument.SINGLETON_ID)
            ?: OpenRouterSettingsDocument()

        if (doc.apiKey.isBlank()) return false

        return try {
            val baseUrl = doc.apiBaseUrl.trimEnd('/')
            val response: OpenRouterModelsResponse = httpClient.get("$baseUrl/models") {
                header("Authorization", "Bearer ${doc.apiKey}")
                header("HTTP-Referer", "https://jervis.app")
            }.body()
            response.data.isNotEmpty()
        } catch (e: Exception) {
            logger.warn { "OpenRouter connection test failed: ${e.message}" }
            false
        }
    }

    /**
     * Get settings for internal use (e.g., by orchestrator proxy).
     */
    suspend fun getSettingsInternal(): OpenRouterSettingsDocument {
        return repository.findById(OpenRouterSettingsDocument.SINGLETON_ID)
            ?: OpenRouterSettingsDocument()
    }

    override suspend fun getModelErrors(): List<ModelErrorDto> {
        return try {
            val baseUrl = routerUrl.trimEnd('/')
            val response: JsonObject = httpClient.get("$baseUrl/route-decision/model-errors").body()
            response.entries.map { (modelId, value) ->
                val obj = value.jsonObject
                ModelErrorDto(
                    modelId = modelId,
                    errorCount = obj["count"]?.jsonPrimitive?.int ?: 0,
                    disabled = obj["disabled"]?.jsonPrimitive?.boolean ?: false,
                    errors = (obj["errors"]?.jsonArray ?: emptyList()).map { entry ->
                        val e = entry.jsonObject
                        ModelErrorEntryDto(
                            message = e["message"]?.jsonPrimitive?.content ?: "",
                            timestamp = e["timestamp"]?.jsonPrimitive?.double ?: 0.0,
                        )
                    },
                )
            }
        } catch (e: Exception) {
            logger.warn { "Failed to fetch model errors from router: ${e.message}" }
            emptyList()
        }
    }

    override suspend fun resetModelError(modelId: String): Boolean {
        return try {
            val baseUrl = routerUrl.trimEnd('/')
            val response: JsonObject = httpClient.post("$baseUrl/route-decision/model-reset") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("model_id" to modelId))
            }.body()
            val reEnabled = response["re_enabled"]?.jsonPrimitive?.boolean ?: false
            if (reEnabled) {
                logger.info { "Model $modelId re-enabled via router" }
            }
            reEnabled
        } catch (e: Exception) {
            logger.warn { "Failed to reset model error via router: ${e.message}" }
            false
        }
    }

    private fun applyFilters(model: OpenRouterModelData, filters: OpenRouterFilters): Boolean {
        val provider = model.id.substringBefore("/", "")

        if (filters.allowedProviders.isNotEmpty() && provider !in filters.allowedProviders) return false
        if (provider in filters.blockedProviders) return false
        if (filters.minContextLength > 0 && (model.contextLength ?: 0) < filters.minContextLength) return false

        val inputPrice = parsePrice(model.pricing?.prompt)
        val outputPrice = parsePrice(model.pricing?.completion)
        if (filters.maxInputPricePerMillion > 0 && inputPrice > filters.maxInputPricePerMillion) return false
        if (filters.maxOutputPricePerMillion > 0 && outputPrice > filters.maxOutputPricePerMillion) return false

        if (filters.requireToolSupport && !model.supportsFunctionCalling()) return false

        if (filters.modelNameFilter.isNotBlank()) {
            val pattern = filters.modelNameFilter.lowercase()
            if (pattern !in model.id.lowercase() && pattern !in model.name.lowercase()) return false
        }

        return true
    }

    private fun parsePrice(price: String?): Double {
        if (price.isNullOrBlank()) return 0.0
        return try {
            // OpenRouter prices are per-token, convert to per-million
            price.toDouble() * 1_000_000
        } catch (_: NumberFormatException) {
            0.0
        }
    }

    private fun OpenRouterSettingsDocument.toDto(): OpenRouterSettingsDto =
        OpenRouterSettingsDto(
            apiKey = this.apiKey,
            apiBaseUrl = this.apiBaseUrl,
            enabled = this.enabled,
            filters = OpenRouterFiltersDto(
                allowedProviders = this.filters.allowedProviders,
                blockedProviders = this.filters.blockedProviders,
                minContextLength = this.filters.minContextLength,
                maxInputPricePerMillion = this.filters.maxInputPricePerMillion,
                maxOutputPricePerMillion = this.filters.maxOutputPricePerMillion,
                requireToolSupport = this.filters.requireToolSupport,
                requireStreaming = this.filters.requireStreaming,
                modelNameFilter = this.filters.modelNameFilter,
            ),
            models = this.models.map { it.toDto() },
            monthlyBudgetUsd = this.monthlyBudgetUsd,
            fallbackStrategy = try {
                OpenRouterFallbackStrategy.valueOf(this.fallbackStrategy)
            } catch (_: Exception) {
                OpenRouterFallbackStrategy.NEXT_IN_LIST
            },
            modelQueues = this.modelQueues.map { it.toDto() },
        )

    private fun OpenRouterModelEntry.toDto(): OpenRouterModelEntryDto =
        OpenRouterModelEntryDto(
            modelId = this.modelId,
            displayName = this.displayName,
            enabled = this.enabled,
            maxContextTokens = this.maxContextTokens,
            inputPricePerMillion = this.inputPricePerMillion,
            outputPricePerMillion = this.outputPricePerMillion,
            preferredFor = this.preferredFor.mapNotNull { name ->
                try { OpenRouterModelUseCase.valueOf(name) } catch (_: Exception) { null }
            },
            maxOutputTokens = this.maxOutputTokens,
        )

    private fun OpenRouterFiltersDto.toEntity(): OpenRouterFilters =
        OpenRouterFilters(
            allowedProviders = this.allowedProviders,
            blockedProviders = this.blockedProviders,
            minContextLength = this.minContextLength,
            maxInputPricePerMillion = this.maxInputPricePerMillion,
            maxOutputPricePerMillion = this.maxOutputPricePerMillion,
            requireToolSupport = this.requireToolSupport,
            requireStreaming = this.requireStreaming,
            modelNameFilter = this.modelNameFilter,
        )

    private fun OpenRouterModelEntryDto.toEntity(): OpenRouterModelEntry =
        OpenRouterModelEntry(
            modelId = this.modelId,
            displayName = this.displayName,
            enabled = this.enabled,
            maxContextTokens = this.maxContextTokens,
            inputPricePerMillion = this.inputPricePerMillion,
            outputPricePerMillion = this.outputPricePerMillion,
            preferredFor = this.preferredFor.map { it.name },
            maxOutputTokens = this.maxOutputTokens,
        )

    private fun ModelQueue.toDto(): ModelQueueDto =
        ModelQueueDto(
            name = this.name,
            models = this.models.map { it.toDto() },
            enabled = this.enabled,
        )

    private fun QueueModelEntry.toDto(): QueueModelEntryDto =
        QueueModelEntryDto(
            modelId = this.modelId,
            isLocal = this.isLocal,
            maxContextTokens = this.maxContextTokens,
            enabled = this.enabled,
            label = this.label,
        )

    private fun ModelQueueDto.toEntity(): ModelQueue =
        ModelQueue(
            name = this.name,
            models = this.models.map { it.toEntity() },
            enabled = this.enabled,
        )

    private fun QueueModelEntryDto.toEntity(): QueueModelEntry =
        QueueModelEntry(
            modelId = this.modelId,
            isLocal = this.isLocal,
            maxContextTokens = this.maxContextTokens,
            enabled = this.enabled,
            label = this.label,
        )
}

// --- Internal DTOs for OpenRouter API responses ---

@Serializable
private data class OpenRouterModelsResponse(
    val data: List<OpenRouterModelData> = emptyList(),
)

@Serializable
private data class OpenRouterModelData(
    val id: String,
    val name: String = "",
    @SerialName("context_length") val contextLength: Int? = null,
    val pricing: OpenRouterPricing? = null,
    @SerialName("supported_parameters") val supportedParameters: List<String> = emptyList(),
) {
    fun supportsFunctionCalling(): Boolean =
        supportedParameters.any { it == "tools" || it == "tool_choice" }
}

@Serializable
private data class OpenRouterPricing(
    val prompt: String? = null,
    val completion: String? = null,
)
