package com.jervis.router.grpc

import com.jervis.contracts.router.InvalidateClientTierRequest
import com.jervis.contracts.router.InvalidateClientTierResponse
import com.jervis.contracts.router.ListModelErrorsRequest
import com.jervis.contracts.router.ListModelErrorsResponse
import com.jervis.contracts.router.ListModelStatsRequest
import com.jervis.contracts.router.ListModelStatsResponse
import com.jervis.contracts.router.MaxContextRequest
import com.jervis.contracts.router.MaxContextResponse
import com.jervis.contracts.router.ModelErrorEntry
import com.jervis.contracts.router.ModelErrorInfo as ProtoModelErrorInfo
import com.jervis.contracts.router.ModelStatInfo
import com.jervis.contracts.router.QueueRateLimit
import com.jervis.contracts.router.RateLimitsRequest
import com.jervis.contracts.router.RateLimitsResponse
import com.jervis.contracts.router.ReportModelErrorRequest
import com.jervis.contracts.router.ReportModelErrorResponse
import com.jervis.contracts.router.ReportModelSuccessRequest
import com.jervis.contracts.router.ReportModelSuccessResponse
import com.jervis.contracts.router.ResetModelErrorRequest
import com.jervis.contracts.router.ResetModelErrorResponse
import com.jervis.contracts.router.RouterAdminServiceGrpcKt
import com.jervis.contracts.router.TestModelRequest
import com.jervis.contracts.router.TestModelResponse
import com.jervis.contracts.router.WhisperDoneRequest
import com.jervis.contracts.router.WhisperDoneResponse
import com.jervis.contracts.router.WhisperNotifyRequest
import com.jervis.contracts.router.WhisperNotifyResponse
import com.jervis.router.coord.ClientTierCache
import com.jervis.router.coord.WhisperCoordinator
import com.jervis.router.proxy.OpenRouterCatalog
import com.jervis.router.proxy.OpenRouterRateLimiters
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

class RouterAdminGrpcImpl(
    private val openRouterCatalog: OpenRouterCatalog,
    private val whisperCoordinator: WhisperCoordinator,
    private val clientTierCache: ClientTierCache,
    private val httpClient: HttpClient,
) : RouterAdminServiceGrpcKt.RouterAdminServiceCoroutineImplBase() {

    override suspend fun getMaxContext(request: MaxContextRequest): MaxContextResponse {
        val tier = when (request.maxTier) {
            com.jervis.contracts.common.TierCap.TIER_CAP_NONE -> "NONE"
            com.jervis.contracts.common.TierCap.TIER_CAP_T1 -> "PAID"
            com.jervis.contracts.common.TierCap.TIER_CAP_T2 -> "PREMIUM"
            else -> "NONE"
        }
        val max = openRouterCatalog.maxContextTokens(tier)
        return MaxContextResponse.newBuilder().setMaxContextTokens(max).build()
    }

    override suspend fun reportModelError(request: ReportModelErrorRequest): ReportModelErrorResponse {
        val justDisabled = openRouterCatalog.reportModelError(
            modelId = request.modelId,
            errorMessage = request.errorMessage,
        )
        val info = openRouterCatalog.listErrors()[request.modelId]
        return ReportModelErrorResponse.newBuilder()
            .setModelId(request.modelId)
            .setDisabled(info?.disabled ?: false)
            .setErrorCount(info?.count ?: 0)
            .setJustDisabled(justDisabled)
            .build()
    }

    override suspend fun reportModelSuccess(request: ReportModelSuccessRequest): ReportModelSuccessResponse {
        val hadErrors = openRouterCatalog.listErrors()[request.modelId]?.count.let { (it ?: 0) > 0 }
        openRouterCatalog.reportModelSuccess(request.modelId)
        openRouterCatalog.recordModelCall(
            modelId = request.modelId,
            durationS = request.durationS,
            inputTokens = request.inputTokens.toInt(),
            outputTokens = request.outputTokens.toInt(),
        )
        return ReportModelSuccessResponse.newBuilder()
            .setModelId(request.modelId)
            .setReset(hadErrors)
            .build()
    }

    override suspend fun listModelErrors(request: ListModelErrorsRequest): ListModelErrorsResponse {
        val errors = openRouterCatalog.listErrors()
        val builder = ListModelErrorsResponse.newBuilder()
        for ((modelId, info) in errors) {
            val protoBuilder = ProtoModelErrorInfo.newBuilder()
                .setModelId(modelId)
                .setCount(info.count)
                .setDisabled(info.disabled)
            for ((message, ts) in info.recentErrors) {
                protoBuilder.addEntries(
                    ModelErrorEntry.newBuilder().setMessage(message).setTimestamp(ts / 1000.0).build(),
                )
            }
            builder.addErrors(protoBuilder.build())
        }
        return builder.build()
    }

    override suspend fun listModelStats(request: ListModelStatsRequest): ListModelStatsResponse {
        val stats = openRouterCatalog.listStats()
        val builder = ListModelStatsResponse.newBuilder()
        for ((modelId, s) in stats) {
            val avg = if (s.callCount > 0) s.totalTimeS / s.callCount else 0.0
            val tps = if (s.totalTimeS > 0) s.totalOutputTokens / s.totalTimeS else 0.0
            builder.addStats(
                ModelStatInfo.newBuilder()
                    .setModelId(modelId)
                    .setCallCount(s.callCount)
                    .setAvgResponseS(avg)
                    .setTotalTimeS(s.totalTimeS)
                    .setTotalInputTokens(s.totalInputTokens.toLong())
                    .setTotalOutputTokens(s.totalOutputTokens.toLong())
                    .setTokensPerS(tps)
                    .setLastCall(s.lastCall)
                    .build(),
            )
        }
        return builder.build()
    }

    override suspend fun resetModelError(request: ResetModelErrorRequest): ResetModelErrorResponse {
        val reEnabled = openRouterCatalog.resetModelError(request.modelId)
        return ResetModelErrorResponse.newBuilder()
            .setModelId(request.modelId)
            .setReEnabled(reEnabled)
            .build()
    }

    override suspend fun testModel(request: TestModelRequest): TestModelResponse {
        val apiKey = openRouterCatalog.apiKey()
            ?: return TestModelResponse.newBuilder()
                .setOk(false)
                .setModelId(request.modelId)
                .setError("OpenRouter API key not configured")
                .build()
        val payload = buildJsonObject {
            put("model", JsonPrimitive(request.modelId))
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive("ping"))
                })
            })
            put("stream", JsonPrimitive(false))
            put("max_tokens", JsonPrimitive(8))
        }
        val started = System.currentTimeMillis()
        return runCatching {
            val response = httpClient.post("https://openrouter.ai/api/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                headers {
                    append(HttpHeaders.Authorization, "Bearer $apiKey")
                    append("HTTP-Referer", "https://jervis.damek-soft.eu")
                    append("X-Title", "Jervis AI Assistant")
                }
                setBody(payload.toString())
                timeout { requestTimeoutMillis = 30.seconds.inWholeMilliseconds }
            }
            val elapsed = (System.currentTimeMillis() - started).toInt()
            val text = response.bodyAsText()
            val builder = TestModelResponse.newBuilder()
                .setModelId(request.modelId)
                .setResponseMs(elapsed)
            if (response.status.isSuccess()) {
                builder.setOk(true).setResponsePreview(text.take(200))
            } else {
                builder.setOk(false).setError("HTTP ${response.status.value}: ${text.take(200)}")
            }
            builder.build()
        }.getOrElse { e ->
            TestModelResponse.newBuilder()
                .setOk(false)
                .setModelId(request.modelId)
                .setResponseMs(((System.currentTimeMillis() - started)).toInt())
                .setError(e.message.orEmpty())
                .build()
        }
    }

    override suspend fun getRateLimits(request: RateLimitsRequest): RateLimitsResponse {
        val builder = RateLimitsResponse.newBuilder()
        for (limiter in listOf(OpenRouterRateLimiters.free, OpenRouterRateLimiters.paid)) {
            val s = limiter.status()
            builder.addQueues(
                QueueRateLimit.newBuilder()
                    .setQueueName(s.name)
                    .setLimit(s.limit)
                    .setRemaining(s.available)
                    .setResetTime((System.currentTimeMillis() + s.nextSlotMs) / 1000.0)
                    .build(),
            )
        }
        return builder.build()
    }

    override suspend fun invalidateClientTier(request: InvalidateClientTierRequest): InvalidateClientTierResponse {
        val target = request.clientId.takeIf { it.isNotBlank() }
        clientTierCache.invalidate(target)
        openRouterCatalog.invalidateSettingsCache()
        return InvalidateClientTierResponse.newBuilder()
            .setInvalidated(target ?: "all")
            .build()
    }

    override suspend fun whisperNotify(request: WhisperNotifyRequest): WhisperNotifyResponse {
        val timeout = if (request.preemptTimeoutS > 0) request.preemptTimeoutS else 30
        val (granted, preempted, unloaded) = whisperCoordinator.notifyWantsGpu(timeout)
        return WhisperNotifyResponse.newBuilder()
            .setGranted(granted)
            .setPreemptedCount(preempted)
            .setUnloadedModels(unloaded)
            .build()
    }

    override suspend fun whisperDone(request: WhisperDoneRequest): WhisperDoneResponse {
        whisperCoordinator.notifyDone()
        return WhisperDoneResponse.newBuilder().setReleased(true).build()
    }
}
