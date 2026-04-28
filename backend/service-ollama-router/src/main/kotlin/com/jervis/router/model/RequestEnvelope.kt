package com.jervis.router.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

sealed interface OutboundChunk {
    val raw: JsonObject
    val done: Boolean

    data class Chat(override val raw: JsonObject, override val done: Boolean) : OutboundChunk
    data class Generate(override val raw: JsonObject, override val done: Boolean) : OutboundChunk
}

data class EmbedResult(
    val vectors: List<List<Float>>,
    val modelUsed: String,
)

enum class ApiPath(val path: String) {
    CHAT("/api/chat"),
    GENERATE("/api/generate"),
    EMBED("/api/embed"),
    EMBEDDINGS("/api/embeddings"),
}

/**
 * Envelope carried through the queue → dispatcher → proxy chain.
 *
 * - Streaming requests (chat/generate): consumer collects from [outbox] until
 *   the dispatcher closes the channel (success) or sends a terminal `done=true`
 *   chunk. On client cancel the outbox `SendChannel` will refuse new items;
 *   the parent [coroutineScope] cancels [cancelToken] and the dispatcher exits
 *   via finally.
 * - Embed (unary): outbox is null; the dispatcher completes [embedResult]
 *   directly so the gRPC servicer can return the response.
 * - Slot wait: when fast-path acquire fails the request is enqueued and the
 *   gRPC handler suspends on [cancelToken] (the dispatcher completes it once
 *   the request is done — success or failure).
 */
data class RequestEnvelope(
    val id: RequestId,
    val priority: Priority,
    val capability: Capability,
    val queueGroup: QueueGroup,
    val apiPath: ApiPath,
    val intent: String,
    val model: String,
    val originalModel: String?,
    val minModelSize: Int,
    val deadline: Instant?,
    val body: JsonObject,
    val outbox: SendChannel<OutboundChunk>?,
    val embedResult: CompletableDeferred<EmbedResult>?,
    val state: AtomicReference<RequestState> = AtomicReference(RequestState.Queued),
    val cancelToken: Job,
    val createdAt: Instant = Instant.now(),
    val reservedBy: String? = null,
)
