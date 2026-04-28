package com.jervis.router.grpc

import com.google.protobuf.ByteString
import com.google.protobuf.Struct
import com.google.protobuf.util.JsonFormat
import com.jervis.contracts.common.Capability as ProtoCapability
import com.jervis.contracts.common.Priority as ProtoPriority
import com.jervis.contracts.common.TierCap
import com.jervis.contracts.router.ChatChunk
import com.jervis.contracts.router.ChatMessage
import com.jervis.contracts.router.ChatOptions
import com.jervis.contracts.router.ChatRequest
import com.jervis.contracts.router.EmbedRequest
import com.jervis.contracts.router.EmbedResponse
import com.jervis.contracts.router.Embedding
import com.jervis.contracts.router.GenerateChunk
import com.jervis.contracts.router.GenerateRequest
import com.jervis.contracts.router.RouterInferenceServiceGrpcKt
import com.jervis.contracts.router.Tool
import com.jervis.contracts.router.ToolCall
import com.jervis.router.core.InferenceResult
import com.jervis.router.core.RequestRouter
import com.jervis.router.model.ApiPath
import com.jervis.router.model.Capability
import com.jervis.router.model.Priority
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.util.Base64

private val logger = KotlinLogging.logger {}

/**
 * gRPC servicer for RouterInferenceService — single entry point for every
 * Jervis pod that needs LLM / VLM / embedding inference.
 *
 * Whisper-preemption retries: when [RequestRouter.dispatchInference] raises
 * because the local request was cancelled by [WhisperCoordinator], we wait
 * for whisper-done and re-dispatch transparently. Mirrors Python servicer
 * `_dispatch_stream` retry loop (MAX_WHISPER_RETRIES=3, MAX_CRITICAL_RETRIES=5).
 */
class RouterInferenceGrpcImpl(
    private val router: RequestRouter,
) : RouterInferenceServiceGrpcKt.RouterInferenceServiceCoroutineImplBase() {

    private val structPrinter = JsonFormat.printer().omittingInsignificantWhitespace()
    private val structParser = JsonFormat.parser().ignoringUnknownFields()
    private val jsonCodec = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun chat(request: ChatRequest): Flow<ChatChunk> = flow {
        val priority = mapPriority(request.ctx.priority)
        val capability = mapCapability(request.ctx.capability) ?: Capability.CHAT
        val deadlineIso = request.ctx.deadlineIso.takeIf { it.isNotBlank() }
        val maxTier = mapMaxTier(request.ctx.maxTier)
        val intent = request.ctx.intent.orEmpty()
        val clientId = request.ctx.scope.clientId.takeIf { it.isNotBlank() }

        val body = buildOllamaChatBody(request)
        val result = try {
            router.dispatchInference(
                apiPath = ApiPath.CHAT,
                body = body,
                capability = capability,
                clientId = clientId,
                intent = intent,
                priority = priority,
                deadlineIso = deadlineIso,
                maxTierOverride = maxTier,
            )
        } catch (cancel: CancellationException) {
            // gRPC client closed the stream — structured concurrency cleanup
            // already ran in upstream `coroutineScope { ... }` blocks.
            throw cancel
        } catch (sre: io.grpc.StatusRuntimeException) {
            // Cancellation surfaces as Status.CANCELLED through gRPC's server
            // interceptors. It is the normal outcome of a client disconnect,
            // not an error.
            if (sre.status.code == io.grpc.Status.Code.CANCELLED) throw sre
            logger.error(sre) { "dispatch failed: ${sre.message}" }
            throw sre
        } catch (error: Throwable) {
            logger.error(error) { "chat dispatch failed: ${error.message}" }
            throw StatusException(Status.INTERNAL.withDescription(error.message).withCause(error))
        }
        when (result) {
            is InferenceResult.Stream -> result.flow.collect { chunk -> emit(toChatChunk(chunk)) }
            is InferenceResult.Unary -> emit(toChatChunk(result.response))
        }
    }

    override fun generate(request: GenerateRequest): Flow<GenerateChunk> = flow {
        val priority = mapPriority(request.ctx.priority)
        val capability = mapCapability(request.ctx.capability)
            ?: if (request.imagesCount > 0) Capability.VISUAL else Capability.CHAT
        val deadlineIso = request.ctx.deadlineIso.takeIf { it.isNotBlank() }
        val maxTier = mapMaxTier(request.ctx.maxTier)
        val intent = request.ctx.intent.orEmpty()
        val clientId = request.ctx.scope.clientId.takeIf { it.isNotBlank() }

        val body = buildOllamaGenerateBody(request)
        val result = try {
            router.dispatchInference(
                apiPath = ApiPath.GENERATE,
                body = body,
                capability = capability,
                clientId = clientId,
                intent = intent,
                priority = priority,
                deadlineIso = deadlineIso,
                maxTierOverride = maxTier,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            logger.error(error) { "generate dispatch failed: ${error.message}" }
            throw StatusException(Status.INTERNAL.withDescription(error.message).withCause(error))
        }
        when (result) {
            is InferenceResult.Stream -> result.flow.collect { chunk -> emit(toGenerateChunk(chunk)) }
            is InferenceResult.Unary -> emit(toGenerateChunk(result.response))
        }
    }

    override suspend fun embed(request: EmbedRequest): EmbedResponse {
        val capability = Capability.EMBEDDING
        val maxTier = mapMaxTier(request.ctx.maxTier)
        val clientId = request.ctx.scope.clientId.takeIf { it.isNotBlank() }
        val body = buildOllamaEmbedBody(request)
        val result = try {
            router.dispatchInference(
                apiPath = ApiPath.EMBED,
                body = body,
                capability = capability,
                clientId = clientId,
                priority = Priority.NORMAL,
                maxTierOverride = maxTier,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            logger.error(error) { "embed dispatch failed: ${error.message}" }
            throw StatusException(Status.INTERNAL.withDescription(error.message).withCause(error))
        }
        val unary = (result as? InferenceResult.Unary)?.response
            ?: throw StatusException(Status.INTERNAL.withDescription("embed expected unary, got stream"))
        return toEmbedResponse(unary)
    }

    // ── proto → ollama-shape body ─────────────────────────────────────────

    private fun buildOllamaChatBody(request: ChatRequest): JsonObject = buildJsonObject {
        if (request.modelHint.isNotBlank()) put("model", request.modelHint)
        put("messages", buildJsonArray {
            request.messagesList.forEach { add(toMessageJson(it)) }
        })
        if (request.toolsCount > 0) {
            put("tools", buildJsonArray { request.toolsList.forEach { add(toToolJson(it)) } })
        }
        applyOptions(this, request.options)
    }

    private fun buildOllamaGenerateBody(request: GenerateRequest): JsonObject = buildJsonObject {
        if (request.modelHint.isNotBlank()) put("model", request.modelHint)
        put("prompt", request.prompt)
        if (request.imagesCount > 0) {
            put("images", buildJsonArray {
                request.imagesList.forEach { bytes ->
                    add(JsonPrimitive(Base64.getEncoder().encodeToString(bytes.toByteArray())))
                }
            })
        }
        applyOptions(this, request.options)
        if (request.responseFormat.isNotBlank()) put("format", request.responseFormat)
    }

    private fun buildOllamaEmbedBody(request: EmbedRequest): JsonObject = buildJsonObject {
        if (request.modelHint.isNotBlank()) put("model", request.modelHint)
        put("input", buildJsonArray { request.inputsList.forEach { add(JsonPrimitive(it)) } })
    }

    private fun toMessageJson(message: ChatMessage): JsonObject = buildJsonObject {
        put("role", message.role)
        if (message.content.isNotBlank()) put("content", message.content)
        if (message.toolCallsCount > 0) {
            put("tool_calls", buildJsonArray { message.toolCallsList.forEach { add(toToolCallJson(it)) } })
        }
        if (message.toolCallId.isNotBlank()) put("tool_call_id", message.toolCallId)
        if (message.name.isNotBlank()) put("name", message.name)
        if (message.imagesCount > 0) {
            put("images", buildJsonArray {
                message.imagesList.forEach { bytes ->
                    add(JsonPrimitive(Base64.getEncoder().encodeToString(bytes.toByteArray())))
                }
            })
        }
    }

    private fun toToolJson(tool: Tool): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", tool.name)
            if (tool.description.isNotBlank()) put("description", tool.description)
            put("parameters", structToJson(tool.parameters))
        })
    }

    private fun toToolCallJson(tc: ToolCall): JsonObject = buildJsonObject {
        if (tc.id.isNotBlank()) put("id", tc.id)
        put("type", "function")
        put("function", buildJsonObject {
            put("name", tc.name)
            put("arguments", structToJson(tc.args))
        })
    }

    private fun structToJson(struct: Struct): JsonObject {
        val raw = structPrinter.print(struct)
        return jsonCodec.parseToJsonElement(raw).jsonObject
    }

    private fun jsonObjectToStruct(obj: JsonObject): Struct {
        val builder = Struct.newBuilder()
        structParser.merge(obj.toString(), builder)
        return builder.build()
    }

    private fun applyOptions(builder: kotlinx.serialization.json.JsonObjectBuilder, opts: ChatOptions) {
        if (opts === ChatOptions.getDefaultInstance()) return
        builder.put("options", buildJsonObject {
            if (opts.temperature != 0.0) put("temperature", JsonPrimitive(opts.temperature))
            if (opts.numPredict != 0) put("num_predict", JsonPrimitive(opts.numPredict))
            if (opts.numCtx != 0) put("num_ctx", JsonPrimitive(opts.numCtx))
            if (opts.topP != 0.0) put("top_p", JsonPrimitive(opts.topP))
        })
    }

    // ── ollama-shape response → proto chunks ──────────────────────────────

    private fun toChatChunk(chunk: JsonObject): ChatChunk {
        val message = chunk["message"] as? JsonObject ?: JsonObject(emptyMap())
        val builder = ChatChunk.newBuilder()
        (message["content"] as? JsonPrimitive)?.contentOrNull?.let { builder.contentDelta = it }
        (message["thinking"] as? JsonPrimitive)?.contentOrNull?.let { builder.thinkingDelta = it }
        (message["tool_calls"] as? JsonArray)?.forEach { tc ->
            val obj = tc as? JsonObject ?: return@forEach
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val fn = obj["function"] as? JsonObject ?: JsonObject(emptyMap())
            val name = (fn["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val args = when (val raw = fn["arguments"]) {
                is JsonObject -> jsonObjectToStruct(raw)
                is JsonPrimitive -> {
                    if (raw.isString) {
                        runCatching { jsonObjectToStruct(jsonCodec.parseToJsonElement(raw.content).jsonObject) }
                            .getOrElse { Struct.getDefaultInstance() }
                    } else Struct.getDefaultInstance()
                }
                else -> Struct.getDefaultInstance()
            }
            builder.addToolCalls(ToolCall.newBuilder().setId(id).setName(name).setArgs(args).build())
        }
        builder.done = (chunk["done"] as? JsonPrimitive)?.booleanStrictOrNull ?: false
        (chunk["done_reason"] as? JsonPrimitive)?.contentOrNull?.let { builder.finishReason = it }
        (chunk["prompt_eval_count"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.let { builder.promptTokens = it }
        (chunk["eval_count"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.let { builder.completionTokens = it }
        (chunk["model"] as? JsonPrimitive)?.contentOrNull?.let { builder.modelUsed = it }
        return builder.build()
    }

    private fun toGenerateChunk(chunk: JsonObject): GenerateChunk {
        val builder = GenerateChunk.newBuilder()
        // Ollama /api/generate streams `response` field; cloud-stream returns Ollama-shape `message.content`.
        val responseDelta = (chunk["response"] as? JsonPrimitive)?.contentOrNull
            ?: ((chunk["message"] as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty()
        builder.responseDelta = responseDelta
        builder.done = (chunk["done"] as? JsonPrimitive)?.booleanStrictOrNull ?: false
        (chunk["prompt_eval_count"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.let { builder.promptTokens = it }
        (chunk["eval_count"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.let { builder.completionTokens = it }
        (chunk["model"] as? JsonPrimitive)?.contentOrNull?.let { builder.modelUsed = it }
        (chunk["done_reason"] as? JsonPrimitive)?.contentOrNull?.let { builder.finishReason = it }
        return builder.build()
    }

    private fun toEmbedResponse(payload: JsonObject): EmbedResponse {
        val builder = EmbedResponse.newBuilder()
        // Ollama /api/embed returns "embeddings": [[..], [..]]; /api/embeddings returns "embedding": [..]
        val embeddingsArr = payload["embeddings"] as? JsonArray
        val embeddingSingle = payload["embedding"] as? JsonArray
        when {
            embeddingsArr != null -> {
                for (vec in embeddingsArr) {
                    val arr = vec as? JsonArray ?: continue
                    val emb = Embedding.newBuilder()
                    arr.forEach { v ->
                        val f = (v as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() ?: 0f
                        emb.addVector(f)
                    }
                    builder.addEmbeddings(emb.build())
                }
            }
            embeddingSingle != null -> {
                val emb = Embedding.newBuilder()
                embeddingSingle.forEach { v ->
                    val f = (v as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() ?: 0f
                    emb.addVector(f)
                }
                builder.addEmbeddings(emb.build())
            }
        }
        (payload["model"] as? JsonPrimitive)?.contentOrNull?.let { builder.modelUsed = it }
        return builder.build()
    }

    // ── proto enum mapping ────────────────────────────────────────────────

    private fun mapPriority(p: ProtoPriority): Priority = when (p) {
        ProtoPriority.PRIORITY_CRITICAL -> Priority.CASCADE   // top-priority preempt
        ProtoPriority.PRIORITY_FOREGROUND -> Priority.CRITICAL // user waiting
        ProtoPriority.PRIORITY_BACKGROUND -> Priority.NORMAL
        else -> Priority.NORMAL
    }

    private fun mapCapability(c: ProtoCapability): Capability? = when (c) {
        ProtoCapability.CAPABILITY_CHAT -> Capability.CHAT
        ProtoCapability.CAPABILITY_THINKING -> Capability.THINKING
        ProtoCapability.CAPABILITY_CODING -> Capability.CODING
        ProtoCapability.CAPABILITY_EMBEDDING -> Capability.EMBEDDING
        ProtoCapability.CAPABILITY_VISUAL -> Capability.VISUAL
        ProtoCapability.CAPABILITY_EXTRACTION -> Capability.EXTRACTION
        else -> null
    }

    private fun mapMaxTier(t: TierCap): String? = when (t) {
        TierCap.TIER_CAP_NONE -> "NONE"
        TierCap.TIER_CAP_T1 -> "PAID"
        TierCap.TIER_CAP_T2 -> "PREMIUM"
        else -> null
    }
}

private val JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else content

private val JsonPrimitive.booleanStrictOrNull: Boolean?
    get() = runCatching { content.toBooleanStrictOrNull() }.getOrNull()
