package com.jervis.infrastructure.grpc

import com.jervis.contracts.server.GetOpenRouterSettingsRequest
import com.jervis.contracts.server.ModelCallStats as ModelCallStatsProto
import com.jervis.contracts.server.ModelQueue as ModelQueueProto
import com.jervis.contracts.server.OpenRouterFilters as OpenRouterFiltersProto
import com.jervis.contracts.server.OpenRouterModelEntry as OpenRouterModelEntryProto
import com.jervis.contracts.server.OpenRouterSettings as OpenRouterSettingsProto
import com.jervis.contracts.server.PersistModelStatsRequest
import com.jervis.contracts.server.PersistModelStatsResponse
import com.jervis.contracts.server.QueueModelEntry as QueueModelEntryProto
import com.jervis.contracts.server.ServerOpenRouterSettingsServiceGrpcKt
import com.jervis.dto.openrouter.ModelCallStatsDto
import com.jervis.dto.openrouter.ModelQueueDto
import com.jervis.dto.openrouter.OpenRouterFallbackStrategy
import com.jervis.dto.openrouter.OpenRouterFiltersDto
import com.jervis.dto.openrouter.OpenRouterModelEntryDto
import com.jervis.dto.openrouter.OpenRouterSettingsDto
import com.jervis.dto.openrouter.QueueModelEntryDto
import com.jervis.rpc.OpenRouterSettingsRpcImpl
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class ServerOpenRouterSettingsGrpcImpl(
    private val openRouterSettings: OpenRouterSettingsRpcImpl,
) : ServerOpenRouterSettingsServiceGrpcKt.ServerOpenRouterSettingsServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun getSettings(
        request: GetOpenRouterSettingsRequest,
    ): OpenRouterSettingsProto = openRouterSettings.getSettings().toProto()

    override suspend fun persistModelStats(
        request: PersistModelStatsRequest,
    ): PersistModelStatsResponse {
        val persisted = openRouterSettings.persistModelStats(
            request.statsList.associate { it.modelId to it },
        )
        logger.debug { "OPENROUTER_STATS_PERSISTED | models=${request.statsCount}" }
        return PersistModelStatsResponse
            .newBuilder()
            .setOk(true)
            .setModels(request.statsCount)
            .build()
    }
}

private fun OpenRouterSettingsDto.toProto(): OpenRouterSettingsProto {
    val builder = OpenRouterSettingsProto.newBuilder()
        .setApiKey(apiKey)
        .setApiBaseUrl(apiBaseUrl)
        .setEnabled(enabled)
        .setFilters(filters.toProto())
        .setMonthlyBudgetUsd(monthlyBudgetUsd)
        .setFallbackStrategy(fallbackStrategy.name)
    models.forEach { builder.addModels(it.toProto()) }
    modelQueues.forEach { builder.addModelQueues(it.toProto()) }
    return builder.build()
}

private fun OpenRouterFiltersDto.toProto(): OpenRouterFiltersProto =
    OpenRouterFiltersProto.newBuilder()
        .addAllAllowedProviders(allowedProviders)
        .addAllBlockedProviders(blockedProviders)
        .setMinContextLength(minContextLength)
        .setMaxInputPricePerMillion(maxInputPricePerMillion)
        .setMaxOutputPricePerMillion(maxOutputPricePerMillion)
        .setRequireToolSupport(requireToolSupport)
        .setRequireStreaming(requireStreaming)
        .setModelNameFilter(modelNameFilter)
        .build()

private fun OpenRouterModelEntryDto.toProto(): OpenRouterModelEntryProto =
    OpenRouterModelEntryProto.newBuilder()
        .setModelId(modelId)
        .setDisplayName(displayName)
        .setEnabled(enabled)
        .setMaxContextTokens(maxContextTokens)
        .setInputPricePerMillion(inputPricePerMillion)
        .setOutputPricePerMillion(outputPricePerMillion)
        .addAllPreferredFor(preferredFor.map { it.name })
        .setMaxOutputTokens(maxOutputTokens)
        .setFree(free)
        .build()

private fun ModelQueueDto.toProto(): ModelQueueProto {
    val builder = ModelQueueProto.newBuilder()
        .setName(name)
        .setEnabled(enabled)
    models.forEach { builder.addModels(it.toProto()) }
    return builder.build()
}

private fun QueueModelEntryDto.toProto(): QueueModelEntryProto =
    QueueModelEntryProto.newBuilder()
        .setModelId(modelId)
        .setIsLocal(isLocal)
        .setMaxContextTokens(maxContextTokens)
        .setEnabled(enabled)
        .setLabel(label)
        .addAllCapabilities(capabilities)
        .setInputPricePerMillion(inputPricePerMillion)
        .setOutputPricePerMillion(outputPricePerMillion)
        .setSupportsTools(supportsTools)
        .setProvider(provider)
        .setStats(stats.toProto())
        .build()

private fun ModelCallStatsDto.toProto(): ModelCallStatsProto =
    ModelCallStatsProto.newBuilder()
        .setCallCount(callCount)
        .setTotalTimeS(totalTimeS)
        .setTotalInputTokens(totalInputTokens)
        .setTotalOutputTokens(totalOutputTokens)
        .setTokensPerS(tokensPerS)
        .setLastCall(lastCall)
        .build()
