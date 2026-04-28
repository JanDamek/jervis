package com.jervis.router.proxy

import com.jervis.contracts.server.ModelStatsEntry
import com.jervis.contracts.server.OpenRouterSettings
import com.jervis.contracts.server.PersistModelStatsRequest
import com.jervis.contracts.common.RequestContext
import com.jervis.router.coord.ServerCallbackClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import kotlin.math.pow

private val logger = KotlinLogging.logger {}

/**
 * In-memory catalog of OpenRouter cloud models + per-model error / stats
 * tracking. Fetches settings from Kotlin server (cached 60 s) and exposes
 * `findCloudModelForContext` (PREMIUM > PAID > FREE) plus disable/probe
 * lifecycle that mirrors Python `openrouter_catalog.py` 1:1.
 */
class OpenRouterCatalog(
    private val serverCallback: ServerCallbackClient,
) {
    @Volatile private var settingsCache: OpenRouterSettings? = null
    @Volatile private var settingsTs: Long = 0L
    private val settingsTtlMs: Long = 60_000L
    private val cacheMutex = Mutex()

    private val errorMutex = Mutex()
    private val errors: MutableMap<String, ModelErrorInfo> = HashMap()

    private val statsMutex = Mutex()
    private val stats: MutableMap<String, ModelStats> = HashMap()

    suspend fun fetchSettings(): OpenRouterSettings? = cacheMutex.withLock {
        val now = System.currentTimeMillis()
        if (settingsCache != null && (now - settingsTs) < settingsTtlMs) return@withLock settingsCache
        val fresh = serverCallback.getOpenRouterSettings()
        if (fresh != null) {
            settingsCache = fresh
            settingsTs = now
            logger.debug { "Fetched OpenRouter settings: ${fresh.modelQueuesCount} queues" }
        }
        settingsCache
    }

    suspend fun apiKey(): String? = fetchSettings()?.apiKey?.takeIf { it.isNotBlank() }

    /**
     * Returns first eligible cloud model, iterating PREMIUM > PAID > FREE.
     * Matches Python `find_cloud_model_for_context`.
     */
    suspend fun findCloudModelForContext(
        estimatedTokens: Int,
        tierLevel: Int,
        skipModels: Set<String> = emptySet(),
        capability: String? = null,
        requireTools: Boolean = false,
        requireStreaming: Boolean = false,
    ): Pair<String, String>? {
        if (tierLevel >= TIER_LEVELS.getValue("PREMIUM")) {
            firstFromQueue("PREMIUM", estimatedTokens, skipModels, capability, requireTools, requireStreaming)?.let {
                return it to "PREMIUM"
            }
        }
        if (tierLevel >= TIER_LEVELS.getValue("PAID")) {
            firstFromQueue("PAID", estimatedTokens, skipModels, capability, requireTools, requireStreaming)?.let {
                return it to "PAID"
            }
        }
        if (tierLevel >= TIER_LEVELS.getValue("FREE")) {
            firstFromQueue("FREE", estimatedTokens, skipModels, capability, requireTools, requireStreaming)?.let {
                return it to "FREE"
            }
        }
        return null
    }

    private suspend fun firstFromQueue(
        queueName: String,
        estimatedTokens: Int,
        skipModels: Set<String>,
        capability: String?,
        requireTools: Boolean,
        requireStreaming: Boolean,
    ): String? {
        val settings = fetchSettings() ?: return null
        val queue = settings.modelQueuesList.firstOrNull { it.name == queueName && it.enabled } ?: return null
        val now = System.currentTimeMillis()
        val capNorm = capability?.let { normalizeCapability(it) }

        for (entry in queue.modelsList) {
            if (entry.isLocal) continue
            if (!entry.enabled) continue
            val modelId = entry.modelId
            if (modelId in skipModels) continue
            if (requireTools && !entry.supportsTools) continue
            if (requireStreaming && !entry.supportsStreaming) continue
            if (capNorm != null) {
                val caps = entry.capabilitiesList.map { normalizeCapability(it) }.toSet()
                if (caps.isNotEmpty() && capNorm !in caps) continue
            }
            // Error / cooldown checks
            errorMutex.withLock {
                val info = errors[modelId]
                if (info != null) {
                    if (info.permanentlyDisabled) return@withLock false
                    if (info.disabledUntil > now) return@withLock false
                    if (info.disabled && !info.needsProbe) {
                        // Auto-recovery for non-rate-limit errors
                        errors.remove(modelId)
                        return@withLock true
                    }
                    if (info.disabled && info.needsProbe && info.disabledUntil <= now) {
                        info.probeReady = true
                        return@withLock false
                    }
                    if (info.disabled) return@withLock false
                }
                true
            }.let { if (!it) continue }

            val maxCtx = entry.maxContextTokens.takeIf { it > 0 } ?: 32_000
            if (estimatedTokens <= maxCtx) {
                logger.info { "Queue $queueName: selected $modelId" }
                return modelId
            }
        }
        return null
    }

    suspend fun reportModelError(
        modelId: String,
        errorMessage: String = "",
        rateLimitResetEpochMs: Long? = null,
        rateLimitScope: String? = null,
    ): Boolean = errorMutex.withLock {
        val info = errors.getOrPut(modelId) { ModelErrorInfo() }
        val now = System.currentTimeMillis()
        info.lastError = now
        if (errorMessage.isNotEmpty()) info.recentErrors.add(errorMessage.take(500) to now)
        while (info.recentErrors.size > MAX_ERROR_HISTORY) info.recentErrors.removeFirst()

        // Rate-limit-reset honored — disable until exact epoch
        if (rateLimitResetEpochMs != null && rateLimitResetEpochMs > 0) {
            info.disabled = true
            info.disabledUntil = rateLimitResetEpochMs
            info.rateLimitScope = rateLimitScope ?: "unknown"
            info.needsProbe = false
            info.consecutive429 = 0
            logger.warn {
                "Model $modelId DISABLED until provider reset (scope=${info.rateLimitScope}, in ${(rateLimitResetEpochMs - now) / 1000}s)"
            }
            return@withLock true
        }

        val msgLower = errorMessage.lowercase()
        val isRateLimit = "429" in msgLower || "rate limit" in msgLower || "too many" in msgLower
        if (isRateLimit) {
            info.consecutive429 += 1
            if (info.consecutive429 >= RATE_LIMIT_DISABLE_AFTER) {
                info.count = MAX_CONSECUTIVE_ERRORS
                info.disabled = true
                info.needsProbe = true
                info.probeFailures = info.probeFailures
                val cooldown = (PROBE_COOLDOWN_MS * PROBE_COOLDOWN_ESCALATION.pow(info.probeFailures.toDouble())).toLong()
                info.disabledUntil = now + cooldown
                logger.warn { "Model $modelId DISABLED after ${info.consecutive429} consecutive 429s (probe in ${cooldown / 1000}s)" }
                return@withLock true
            }
            info.disabledUntil = now + RATE_LIMIT_PAUSE_MS
            logger.warn { "Model $modelId RATE LIMITED (429 #${info.consecutive429}/$RATE_LIMIT_DISABLE_AFTER) — paused" }
            return@withLock false
        }

        // Context overflow — not an error worth disabling
        val isContextOverflow = listOf(
            "context length", "context_length", "too long", "maximum context",
            "token limit", "exceeds", "max_tokens", "prompt is too long",
            "input too long", "request too large",
        ).any { it in msgLower }
        if (isContextOverflow) {
            logger.warn { "Model $modelId CONTEXT OVERFLOW — not counting as error" }
            return@withLock false
        }

        info.count += 1
        if (info.count >= MAX_CONSECUTIVE_ERRORS && !info.disabled) {
            info.disabled = true
            info.disabledUntil = now + AUTO_RECOVERY_MS
            logger.warn { "Model $modelId DISABLED after ${info.count} consecutive errors (auto-recovery in ${AUTO_RECOVERY_MS / 1000}s)" }
            return@withLock true
        }
        logger.info { "Model $modelId error count: ${info.count}/$MAX_CONSECUTIVE_ERRORS" }
        false
    }

    suspend fun reportModelSuccess(modelId: String) {
        errorMutex.withLock {
            val info = errors[modelId]
            if (info != null && info.count > 0) {
                logger.info { "Model $modelId error counter reset (success)" }
            }
            errors.remove(modelId)
        }
    }

    suspend fun resetModelError(modelId: String): Boolean = errorMutex.withLock {
        val info = errors.remove(modelId)
        if (info != null && info.disabled) {
            logger.info { "Model $modelId re-enabled by user" }
            true
        } else false
    }

    suspend fun listErrors(): Map<String, ModelErrorInfo> = errorMutex.withLock { errors.toMap() }

    suspend fun recordModelCall(
        modelId: String,
        durationS: Double,
        inputTokens: Int = 0,
        outputTokens: Int = 0,
    ) = statsMutex.withLock {
        val s = stats.getOrPut(modelId) { ModelStats() }
        s.callCount += 1
        s.totalTimeS += durationS
        s.totalInputTokens += inputTokens
        s.totalOutputTokens += outputTokens
        s.lastCall = System.currentTimeMillis() / 1000.0
    }

    suspend fun listStats(): Map<String, ModelStats> = statsMutex.withLock { stats.toMap() }

    suspend fun persistStats() {
        val snapshot = listStats()
        if (snapshot.isEmpty()) return
        val req = PersistModelStatsRequest.newBuilder()
            .setCtx(RequestContext.newBuilder().setRequestId("").putTrace("caller", "ollama-router").build())
            .apply {
                for ((modelId, s) in snapshot) {
                    addStats(
                        ModelStatsEntry.newBuilder()
                            .setModelId(modelId)
                            .setCallCount(s.callCount)
                            .setAvgResponseS(if (s.callCount > 0) s.totalTimeS / s.callCount else 0.0)
                            .setTotalTimeS(s.totalTimeS)
                            .setTotalInputTokens(s.totalInputTokens.toLong())
                            .setTotalOutputTokens(s.totalOutputTokens.toLong())
                            .setTokensPerS(if (s.totalTimeS > 0) s.totalOutputTokens / s.totalTimeS else 0.0)
                            .setLastCall(s.lastCall)
                            .build(),
                    )
                }
            }
            .build()
        val resp = serverCallback.persistModelStats(req)
        if (resp?.ok == true) logger.info { "Persisted stats for ${resp.models} models to MongoDB" }
        else logger.warn { "Router-side stats persist failed" }
    }

    suspend fun loadPersistedStats() {
        val settings = fetchSettings() ?: return
        var count = 0
        for (queue in settings.modelQueuesList) {
            for (entry in queue.modelsList) {
                val s = entry.stats
                if (s.callCount > 0) {
                    statsMutex.withLock {
                        stats[entry.modelId] = ModelStats(
                            callCount = s.callCount,
                            totalTimeS = s.totalTimeS,
                            totalInputTokens = s.totalInputTokens.toInt(),
                            totalOutputTokens = s.totalOutputTokens.toInt(),
                            lastCall = s.lastCall,
                        )
                    }
                    count += 1
                }
            }
        }
        if (count > 0) logger.info { "Loaded persisted stats for $count models" }
    }

    suspend fun maxContextTokens(maxTier: String): Long {
        val tierLevel = TIER_LEVELS[normalizeTier(maxTier)] ?: 0
        if (tierLevel == 0) return LOCAL_MAX_CTX
        val settings = fetchSettings() ?: return LOCAL_MAX_CTX
        val queues = mutableListOf("FREE")
        if (tierLevel >= TIER_LEVELS.getValue("PAID")) queues.add("PAID")
        if (tierLevel >= TIER_LEVELS.getValue("PREMIUM")) queues.add("PREMIUM")
        var max = LOCAL_MAX_CTX
        for (queueName in queues) {
            val queue = settings.modelQueuesList.firstOrNull { it.name == queueName } ?: continue
            for (entry in queue.modelsList) {
                if (entry.isLocal) continue
                val ctx = entry.maxContextTokens.takeIf { it > 0 } ?: 32_000
                if (ctx > max) max = ctx.toLong()
            }
        }
        return max
    }

    fun invalidateSettingsCache() {
        settingsCache = null
        settingsTs = 0
    }

    companion object {
        val TIER_LEVELS: Map<String, Int> = mapOf(
            "NONE" to 0,
            "FREE" to 1,
            "PAID" to 2,
            "PREMIUM" to 3,
        )

        private val TIER_COMPAT: Map<String, String> = mapOf(
            "PAID_LOW" to "PAID",
            "PAID_HIGH" to "PREMIUM",
        )

        fun normalizeTier(tier: String): String = TIER_COMPAT[tier] ?: tier

        private val CAPABILITY_ALIASES: Map<String, String> = mapOf(
            "vision" to "visual",
            "vlm" to "visual",
            "text" to "chat",
            "llm" to "chat",
            "code" to "coding",
            "embed" to "embedding",
        )

        fun normalizeCapability(cap: String): String {
            val lower = cap.trim().lowercase()
            return CAPABILITY_ALIASES[lower] ?: lower
        }

        const val LOCAL_MAX_CTX: Long = 250_000

        private const val MAX_CONSECUTIVE_ERRORS = 3
        private const val MAX_ERROR_HISTORY = 10
        private const val RATE_LIMIT_PAUSE_MS = 15_000L
        private const val RATE_LIMIT_DISABLE_AFTER = 5
        private const val PROBE_COOLDOWN_MS = 60_000L
        private const val PROBE_COOLDOWN_ESCALATION = 6.0
        private const val AUTO_RECOVERY_MS = 300_000L
    }
}

class ModelErrorInfo(
    var count: Int = 0,
    var lastError: Long = 0,
    var disabled: Boolean = false,
    var disabledUntil: Long = 0,
    var consecutive429: Int = 0,
    var needsProbe: Boolean = false,
    var probeReady: Boolean = false,
    var probeFailures: Int = 0,
    var permanentlyDisabled: Boolean = false,
    var rateLimitScope: String = "",
    val recentErrors: ArrayDeque<Pair<String, Long>> = ArrayDeque(),
)

class ModelStats(
    var callCount: Int = 0,
    var totalTimeS: Double = 0.0,
    var totalInputTokens: Int = 0,
    var totalOutputTokens: Int = 0,
    var lastCall: Double = 0.0,
)
