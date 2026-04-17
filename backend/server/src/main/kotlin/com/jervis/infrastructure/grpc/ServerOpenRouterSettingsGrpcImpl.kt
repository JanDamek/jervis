package com.jervis.infrastructure.grpc

import com.jervis.contracts.server.GetOpenRouterSettingsRequest
import com.jervis.contracts.server.GetOpenRouterSettingsResponse
import com.jervis.contracts.server.PersistModelStatsRequest
import com.jervis.contracts.server.PersistModelStatsResponse
import com.jervis.contracts.server.ServerOpenRouterSettingsServiceGrpcKt
import com.jervis.rpc.OpenRouterSettingsRpcImpl
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class ServerOpenRouterSettingsGrpcImpl(
    private val openRouterSettings: OpenRouterSettingsRpcImpl,
) : ServerOpenRouterSettingsServiceGrpcKt.ServerOpenRouterSettingsServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun getSettings(
        request: GetOpenRouterSettingsRequest,
    ): GetOpenRouterSettingsResponse {
        val settings = openRouterSettings.getSettings()
        return GetOpenRouterSettingsResponse
            .newBuilder()
            .setBodyJson(json.encodeToString(settings))
            .build()
    }

    override suspend fun persistModelStats(
        request: PersistModelStatsRequest,
    ): PersistModelStatsResponse {
        val stats = json.parseToJsonElement(request.statsJson).jsonObject
        openRouterSettings.persistModelStats(stats)
        logger.debug { "OPENROUTER_STATS_PERSISTED | models=${stats.size}" }
        return PersistModelStatsResponse
            .newBuilder()
            .setOk(true)
            .setModels(stats.size)
            .build()
    }
}
