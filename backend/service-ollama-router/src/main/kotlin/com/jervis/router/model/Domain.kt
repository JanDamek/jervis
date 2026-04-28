package com.jervis.router.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@JvmInline
value class RequestId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class GpuName(val value: String) {
    override fun toString(): String = value
}

enum class Priority(val rank: Int) {
    CASCADE(-1),
    CRITICAL(0),
    NORMAL(1);

    companion object {
        fun fromRank(rank: Int): Priority = entries.firstOrNull { it.rank == rank } ?: NORMAL
    }
}

enum class QueueGroup { EMBED, LLM, VLM }

enum class Capability(val tag: String) {
    THINKING("thinking"),
    CODING("coding"),
    CHAT("chat"),
    EMBEDDING("embedding"),
    VISUAL("visual"),
    EXTRACTION("extraction"),
    ;

    companion object {
        fun fromTag(value: String?): Capability {
            val normalised = value?.trim()?.lowercase() ?: return CHAT
            return entries.firstOrNull { it.tag == normalised }
                ?: when (normalised) {
                    "vision", "vlm" -> VISUAL
                    else -> CHAT
                }
        }
    }
}

fun capabilityToQueueGroup(capability: Capability): QueueGroup = when (capability) {
    Capability.EMBEDDING -> QueueGroup.EMBED
    Capability.VISUAL -> QueueGroup.VLM
    else -> QueueGroup.LLM
}

enum class TierCap {
    NONE,
    FREE,
    PAID,
    PREMIUM,
    UNSPECIFIED,
}

enum class Bucket {
    REALTIME,
    URGENT,
    NORMAL,
    BATCH,
}

sealed interface RequestState {
    data object Queued : RequestState
    data object LoadingModel : RequestState
    data class Running(val gpu: GpuName, val startedAt: Instant) : RequestState
    data class Preempted(val reason: PreemptReason, val emittedChunks: Int) : RequestState
    data object Completed : RequestState
    data class Failed(val cause: ProxyError) : RequestState
}

enum class PreemptReason { WHISPER, CRITICAL_PEER }

sealed class ProxyError(message: String) : RuntimeException(message) {
    data object UnknownBackend : ProxyError("unknown backend")
    data class UpstreamError(val status: Int, val body: String) : ProxyError("upstream $status: $body")
    data class StuckBackend(val ms: Long) : ProxyError("backend stuck for $ms ms")
    data class GpuExhausted(val model: String) : ProxyError("no GPU has free VRAM for $model")
    data class RateLimited(val resetEpochMs: Long?) : ProxyError("rate limited")
    data class ModelDisabled(val model: String) : ProxyError("model $model disabled by error budget")
    data object PreemptedByWhisper : ProxyError("preempted by whisper")
    data object PreemptedByCritical : ProxyError("preempted by CRITICAL peer")
}
