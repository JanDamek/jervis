package com.jervis.router.core

import com.jervis.router.config.ModelCatalog
import com.jervis.router.config.RouterConfig
import com.jervis.router.coord.ClientTierCache
import com.jervis.router.coord.WhisperCoordinator
import com.jervis.router.gpu.GpuPool
import com.jervis.router.model.ApiPath
import com.jervis.router.model.Bucket
import com.jervis.router.model.Capability
import com.jervis.router.model.PreemptReason
import com.jervis.router.model.Priority
import com.jervis.router.model.ProxyError
import com.jervis.router.model.QueueGroup
import com.jervis.router.model.RequestEnvelope
import com.jervis.router.model.RequestId
import com.jervis.router.model.RequestState
import com.jervis.router.proxy.OpenRouterCatalog
import com.jervis.router.proxy.OpenRouterProxy
import com.jervis.router.proxy.OpenRouterRateLimiters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private val logger = KotlinLogging.logger {}

sealed interface RouteDecision {
    data class Local(val model: String) : RouteDecision
    data class Cloud(val model: String, val apiKey: String, val queue: String) : RouteDecision
}

sealed interface InferenceResult {
    data class Stream(val flow: Flow<JsonObject>) : InferenceResult
    data class Unary(val response: JsonObject) : InferenceResult
}

class RequestRouter(
    private val config: RouterConfig,
    private val gpuPool: GpuPool,
    private val requestQueue: RequestQueue,
    private val openRouterCatalog: OpenRouterCatalog,
    private val openRouterProxy: OpenRouterProxy,
    private val whisperCoordinator: WhisperCoordinator,
    private val clientTierCache: ClientTierCache,
) {

    /**
     * Single-pass route + dispatch. Returns a flow (chat/generate streaming)
     * or a unary JSON object (embeddings). Mirrors Python `dispatch_inference`.
     *
     * Cascade: caller tier → FREE → NONE (local). 402/429 on cloud step out
     * to the next tier. All other ProxyErrors surface to the caller.
     */
    suspend fun dispatchInference(
        apiPath: ApiPath,
        body: JsonObject,
        capability: Capability? = null,
        clientId: String? = null,
        intent: String = "",
        priority: Priority = Priority.NORMAL,
        deadlineIso: String? = null,
        maxTierOverride: String? = null,
    ): InferenceResult {
        val cap = capability ?: detectCapabilityFromBody(apiPath, body)
        val requireTools = (body["tools"] as? JsonArray)?.isNotEmpty() == true
        val isStreamingPath = apiPath != ApiPath.EMBED && apiPath != ApiPath.EMBEDDINGS &&
            ((body["stream"] as? JsonPrimitive)?.booleanOrNull ?: true)
        val estimatedTokens = estimateTokens(body)

        val tierCascade = buildTierCascade(maxTierOverride)
        var lastError: ProxyError? = null

        for (tierForAttempt in tierCascade) {
            val decision = decideRoute(
                capability = cap,
                estimatedTokens = estimatedTokens,
                clientId = clientId,
                requireTools = requireTools,
                requireStreaming = isStreamingPath,
                maxTierOverride = tierForAttempt,
            )

            when (decision) {
                is RouteDecision.Cloud -> {
                    val requestId = "inf-${UUID.randomUUID().toString().take(6)}"
                    val slotOk = OpenRouterRateLimiters.acquire(decision.queue)
                    if (!slotOk) {
                        logger.warn { "ROUTE: rate limit on ${decision.queue} queue, cascade next" }
                        continue
                    }
                    try {
                        return if (isStreamingPath) {
                            // Prime the stream so 402/429 on first chunk triggers cascade
                            // instead of surfacing mid-flow. Matches Python `_replay`.
                            val flow = openRouterProxy.stream(body, decision.model, decision.apiKey, requestId)
                            val primed = primeFlow(flow)
                            InferenceResult.Stream(primed)
                        } else {
                            InferenceResult.Unary(
                                openRouterProxy.unary(body, decision.model, decision.apiKey, requestId),
                            )
                        }
                    } catch (e: ProxyError.RateLimited) {
                        openRouterCatalog.reportModelError(
                            decision.model,
                            errorMessage = "429: rate limited",
                            rateLimitResetEpochMs = e.resetEpochMs,
                        )
                        logger.warn { "CLOUD_CASCADE: tier=$tierForAttempt rate limited — cascade" }
                        lastError = e
                        continue
                    } catch (e: ProxyError.UpstreamError) {
                        if (e.status == 402 || e.status == 429) {
                            openRouterCatalog.reportModelError(
                                decision.model,
                                errorMessage = "${e.status}: ${e.body.take(200)}",
                            )
                            logger.warn { "CLOUD_CASCADE: tier=$tierForAttempt status=${e.status} — cascade" }
                            lastError = e
                            continue
                        }
                        throw e
                    }
                }
                is RouteDecision.Local -> {
                    val rewrittenBody = rewriteModel(body, decision.model)
                    val envelope = buildEnvelope(
                        apiPath = apiPath,
                        body = rewrittenBody,
                        capability = cap,
                        priority = priority,
                        intent = intent,
                        deadlineIso = deadlineIso,
                        model = decision.model,
                    )
                    val result = requestQueue.submit(envelope)
                    return when (result) {
                        is DispatchResult.Stream -> InferenceResult.Stream(result.flow)
                        is DispatchResult.Unary -> InferenceResult.Unary(result.response)
                    }
                }
            }
        }
        if (lastError != null) throw lastError
        throw IllegalStateException("dispatch cascade exhausted without dispatching")
    }

    private suspend fun decideRoute(
        capability: Capability,
        estimatedTokens: Int,
        clientId: String?,
        requireTools: Boolean,
        requireStreaming: Boolean,
        maxTierOverride: String?,
    ): RouteDecision {
        val tier = when {
            !maxTierOverride.isNullOrBlank() -> OpenRouterCatalog.normalizeTier(maxTierOverride)
            !clientId.isNullOrBlank() -> OpenRouterCatalog.normalizeTier(clientTierCache.resolve(clientId))
            else -> "NONE"
        }
        val tierLevel = OpenRouterCatalog.TIER_LEVELS[tier] ?: 0
        val localModel = findLocalModelForCapability(capability) ?: config.orchestratorModel
        val localFallback = RouteDecision.Local(localModel)

        suspend fun tryCloud(reason: String): RouteDecision.Cloud? {
            val (cloudModel, queue) = openRouterCatalog.findCloudModelForContext(
                estimatedTokens = estimatedTokens,
                tierLevel = tierLevel,
                capability = capability.tag,
                requireTools = requireTools,
                requireStreaming = requireStreaming,
            ) ?: return null
            val apiKey = openRouterCatalog.apiKey() ?: return null
            logger.info { "Route decision: $reason → cloud $cloudModel (tier=$tier, tokens=$estimatedTokens)" }
            return RouteDecision.Cloud(cloudModel, apiKey, queue)
        }

        // 1. Embeddings — local only.
        if (capability == Capability.EMBEDDING) {
            logger.info { "Route decision: embedding → local ($localModel)" }
            return localFallback
        }
        // 2. tier=NONE → local only.
        if (tierLevel == 0) {
            logger.info { "Route decision: tier=NONE → local (cap=${capability.tag}, model=$localModel)" }
            return localFallback
        }
        // 3. VLM — local preferred, escalate when blocked.
        if (capability == Capability.VISUAL) {
            val audioBusy = whisperCoordinator.isBusy()
            val vlmBlocked = audioBusy || gpuPool.all.any { backend ->
                backend.name.value == ModelCatalog.VLM_GPU && (
                    !backend.healthy || backend.activeRequestCount() > 0 || backend.loadingInProgress
                )
            }
            if (vlmBlocked) {
                tryCloud(if (audioBusy) "VLM GPU busy (audio)" else "VLM GPU busy / loading")?.let { return it }
                logger.warn { "VLM blocked locally and no cloud VLM fits — queuing on local" }
            }
            logger.info { "Route decision: visual → local VLM ($localModel)" }
            return localFallback
        }
        // 4. User-facing (chat, thinking) → cloud always.
        if (capability == Capability.CHAT || capability == Capability.THINKING) {
            tryCloud("capability=${capability.tag} (user-facing) tier=$tier")?.let { return it }
            logger.warn {
                "Route decision: capability=${capability.tag} wanted cloud but none fits " +
                    "(tokens=$estimatedTokens, tier=$tier) — degrading to local"
            }
            return localFallback
        }
        // 5. Background (coding, extraction, …) — local if GPU idle & ≤ 40k.
        val localCtxSafeBudget = 40_000
        val audioBusy = whisperCoordinator.isBusy()
        val totalQueued = requestQueue.queueDepth().values.sum()
        val gpuFree = totalQueued == 0 && gpuPool.all.any { backend ->
            backend.healthy && backend.activeRequestCount() == 0 &&
                !(backend.name.value == ModelCatalog.VLM_GPU && audioBusy) &&
                localModel in (ModelCatalog.gpuModelSets[backend.name.value].orEmpty())
        }
        if (estimatedTokens <= localCtxSafeBudget && gpuFree) {
            logger.info { "Route decision: capability=${capability.tag} → local model=$localModel (tokens=$estimatedTokens)" }
            return localFallback
        }
        val reason = if (estimatedTokens > localCtxSafeBudget) {
            "capability=${capability.tag} oversize (tokens=$estimatedTokens > $localCtxSafeBudget)"
        } else {
            "capability=${capability.tag} GPU busy"
        }
        tryCloud(reason)?.let { return it }
        logger.info { "Route decision: $reason → local fallback (model=$localModel)" }
        return localFallback
    }

    private fun findLocalModelForCapability(capability: Capability): String? {
        val tag = capability.tag
        val candidates = ModelCatalog.localModelCapabilities.filter { (_, caps) ->
            tag in caps
        }.keys.toList()
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        // Prefer model on a free GPU
        for (model in candidates) {
            for (backend in gpuPool.all) {
                val gpuSet = ModelCatalog.gpuModelSets[backend.name.value].orEmpty()
                if (model !in gpuSet) continue
                if (backend.healthy && backend.activeRequestCount() == 0) return model
            }
        }
        return candidates.first()
    }

    private fun buildTierCascade(currentOverride: String?): List<String?> {
        val seen = mutableSetOf<String?>()
        val result = mutableListOf<String?>()
        for (step in listOf(currentOverride, "FREE", "NONE")) {
            val key = step?.uppercase()?.takeIf { it.isNotEmpty() }
            if (key in seen) continue
            seen.add(key)
            result.add(step)
        }
        return result
    }

    private fun primeFlow(source: Flow<JsonObject>): Flow<JsonObject> = flow {
        val collector: FlowCollector<JsonObject> = this
        var firstEmitted = false
        source.collect { chunk ->
            if (!firstEmitted) firstEmitted = true
            collector.emit(chunk)
        }
    }

    private fun rewriteModel(body: JsonObject, model: String): JsonObject = buildJsonObject {
        for ((k, v) in body.entries) {
            if (k == "model") put("model", model) else put(k, v)
        }
        if ("model" !in body) put("model", model)
    }

    private fun buildEnvelope(
        apiPath: ApiPath,
        body: JsonObject,
        capability: Capability,
        priority: Priority,
        intent: String,
        deadlineIso: String?,
        model: String,
    ): RequestEnvelope {
        val deadline = deadlineIso?.let { parseDeadlineIso(it) }
        val streamingPath = apiPath != ApiPath.EMBED && apiPath != ApiPath.EMBEDDINGS
        val outbox: SendChannel<com.jervis.router.model.OutboundChunk>? = if (streamingPath) Channel(Channel.UNLIMITED) else null
        val embedDeferred: CompletableDeferred<com.jervis.router.model.EmbedResult>? =
            if (!streamingPath) CompletableDeferred() else null
        return RequestEnvelope(
            id = RequestId(UUID.randomUUID().toString()),
            priority = priority,
            capability = capability,
            queueGroup = QueueGroup.LLM.let { capabilityToQueueGroup(capability) },
            apiPath = apiPath,
            intent = intent,
            model = model,
            originalModel = (body["model"] as? JsonPrimitive)?.contentOrNull,
            minModelSize = 0,
            deadline = deadline,
            body = body,
            outbox = outbox,
            embedResult = embedDeferred,
            cancelToken = SupervisorJob() as Job,
        )
    }

    private fun capabilityToQueueGroup(capability: Capability): QueueGroup =
        com.jervis.router.model.capabilityToQueueGroup(capability)

    fun bucketFromDeadline(deadlineIso: String?, priority: Priority): Bucket {
        if (priority == Priority.CASCADE) return Bucket.REALTIME
        val dt = deadlineIso?.let { parseDeadlineIso(it) } ?: return Bucket.BATCH
        val remainingS = (dt.toEpochMilli() - System.currentTimeMillis()) / 1000
        return when {
            remainingS < 10 -> Bucket.REALTIME
            remainingS < 300 -> Bucket.URGENT
            remainingS < 3600 -> Bucket.NORMAL
            else -> Bucket.BATCH
        }
    }

    private fun parseDeadlineIso(iso: String): Instant? = runCatching {
        OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
    }.getOrNull()
}

internal fun detectCapabilityFromBody(apiPath: ApiPath, body: JsonObject): Capability {
    if (apiPath == ApiPath.EMBED || apiPath == ApiPath.EMBEDDINGS) return Capability.EMBEDDING
    val images = body["images"] as? JsonArray
    if (images != null && images.isNotEmpty()) return Capability.VISUAL
    val messages = body["messages"] as? JsonArray
    if (messages != null) {
        for (m in messages) {
            val msg = m as? JsonObject ?: continue
            if ((msg["images"] as? JsonArray)?.isNotEmpty() == true) return Capability.VISUAL
            val content = msg["content"] as? JsonArray
            if (content != null) {
                for (part in content) {
                    val partObj = part as? JsonObject ?: continue
                    val type = (partObj["type"] as? JsonPrimitive)?.contentOrNull
                    if (type == "image" || type == "image_url") return Capability.VISUAL
                }
            }
        }
    }
    return Capability.CHAT
}

internal fun estimateTokens(body: JsonObject): Int {
    val messages = body["messages"] as? JsonArray
    if (messages != null && messages.isNotEmpty()) {
        val totalChars = messages.sumOf { entry ->
            ((entry as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull?.length ?: 0
        }
        return maxOf(totalChars / 4, 100)
    }
    val prompt = (body["prompt"] as? JsonPrimitive)?.contentOrNull ?: return 100
    return maxOf(prompt.length / 4, 100)
}

private val JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else content

private val JsonPrimitive.booleanOrNull: Boolean?
    get() = runCatching { content.toBooleanStrictOrNull() }.getOrNull()
