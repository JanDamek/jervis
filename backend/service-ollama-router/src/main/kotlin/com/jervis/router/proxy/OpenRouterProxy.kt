package com.jervis.router.proxy

import com.jervis.router.model.ProxyError
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

class OpenRouterProxy(
    private val httpClient: HttpClient,
    private val baseUrl: String = "https://openrouter.ai/api/v1",
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Stream chat completions from OpenRouter, yielding Ollama-shape JSON
     * chunks: `{model, message:{role:assistant, content, tool_calls?}, done, done_reason?}`.
     */
    fun stream(
        body: JsonObject,
        cloudModel: String,
        apiKey: String,
        requestId: String,
    ): Flow<JsonObject> = flow {
        val openaiBody = buildOpenAiBody(body, cloudModel, stream = true)
        val start = System.currentTimeMillis()
        var firstChunkAt: Long? = null
        var contentChunks = 0
        var totalContentBytes = 0
        var keepalives = 0
        var nonDataLines = 0
        var errorPayloads = 0

        httpClient.preparePost("$baseUrl/chat/completions") {
            headers { applyOpenRouter(this, apiKey) }
            contentType(ContentType.Application.Json)
            setBody(openaiBody.toString())
            timeout {
                requestTimeoutMillis = Long.MAX_VALUE
                socketTimeoutMillis = Long.MAX_VALUE
                connectTimeoutMillis = 10.seconds.inWholeMilliseconds
            }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val errorText = runCatching { response.bodyAsText() }.getOrDefault("").take(2000)
                logger.warn { "OPENROUTER_PROXY: $requestId error ${response.status.value}: ${errorText.take(200)}" }
                val (resetMs, scope) = if (response.status.value == 429) parseRateLimitHeaders(errorText) else null to null
                throw if (response.status.value == 429) ProxyError.RateLimited(resetMs)
                else ProxyError.UpstreamError(response.status.value, errorText.take(500))
            }
            logger.info { "OPENROUTER_PROXY: $requestId connected model=$cloudModel" }
            val channel = response.bodyAsChannel()
            while (true) {
                val raw = channel.readUTF8Line() ?: break
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line == "data: [DONE]") continue
                if (line.startsWith(":")) {
                    keepalives++
                    if (keepalives <= 3 || keepalives % 20 == 0) {
                        logger.info {
                            "OPENROUTER_KEEPALIVE: $requestId n=$keepalives t=${(System.currentTimeMillis() - start)}ms"
                        }
                    }
                    continue
                }
                if (!line.startsWith("data: ")) {
                    nonDataLines++
                    logger.warn {
                        "OPENROUTER_NON_DATA_LINE: $requestId t=${(System.currentTimeMillis() - start)}ms line=${raw.take(300)}"
                    }
                    continue
                }
                val payloadText = line.removePrefix("data: ")
                val chunk = runCatching { json.parseToJsonElement(payloadText).jsonObject }.getOrNull()
                if (chunk == null) {
                    nonDataLines++
                    logger.warn { "OPENROUTER_BAD_JSON: $requestId line=${raw.take(300)}" }
                    continue
                }
                if (chunk["error"] is JsonObject) {
                    errorPayloads++
                    logger.warn { "OPENROUTER_STREAM_ERROR: $requestId error=${chunk["error"]}" }
                    continue
                }
                val choices = chunk["choices"] as? JsonArray ?: continue
                val choice = choices.firstOrNull() as? JsonObject ?: continue
                val delta = choice["delta"] as? JsonObject ?: JsonObject(emptyMap())
                val finish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
                val content = (delta["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val toolCalls = delta["tool_calls"] as? JsonArray

                if (content.isNotEmpty() || toolCalls != null) {
                    if (firstChunkAt == null) {
                        val now = System.currentTimeMillis()
                        firstChunkAt = now
                        logger.info {
                            "OPENROUTER_FIRST_CHUNK: $requestId model=$cloudModel ttft=${now - start}ms"
                        }
                    }
                    contentChunks++
                    totalContentBytes += content.length
                }
                if (finish != null) {
                    logger.info {
                        "OPENROUTER_FINISH: $requestId model=$cloudModel finish=$finish " +
                            "chunks=$contentChunks bytes=$totalContentBytes t=${System.currentTimeMillis() - start}ms"
                    }
                }

                emit(buildOllamaChunk(cloudModel, content, toolCalls, finish, done = finish != null))
            }
            logger.info {
                "OPENROUTER_PROXY: $requestId completed in ${System.currentTimeMillis() - start}ms " +
                    "model=$cloudModel chunks=$contentChunks bytes=$totalContentBytes " +
                    "keepalives=$keepalives non_data=$nonDataLines errors=$errorPayloads"
            }
        }
    }

    /**
     * Non-streaming OpenRouter call. Returns Ollama-shape JSON object.
     */
    suspend fun unary(
        body: JsonObject,
        cloudModel: String,
        apiKey: String,
        requestId: String,
    ): JsonObject {
        val openaiBody = buildOpenAiBody(body, cloudModel, stream = false)
        val start = System.currentTimeMillis()
        val response = httpClient.post("$baseUrl/chat/completions") {
            headers { applyOpenRouter(this, apiKey) }
            contentType(ContentType.Application.Json)
            setBody(openaiBody.toString())
            timeout {
                requestTimeoutMillis = 120.seconds.inWholeMilliseconds
                connectTimeoutMillis = 10.seconds.inWholeMilliseconds
            }
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val (resetMs, _) = if (response.status.value == 429) parseRateLimitHeaders(text) else null to null
            throw if (response.status.value == 429) ProxyError.RateLimited(resetMs)
            else ProxyError.UpstreamError(response.status.value, text.take(500))
        }
        val data = json.parseToJsonElement(text).jsonObject
        val choice = (data["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: JsonObject(emptyMap())
        val message = choice["message"] as? JsonObject ?: JsonObject(emptyMap())
        val content = (message["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val toolCalls = message["tool_calls"] as? JsonArray
        val finish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        val usage = data["usage"] as? JsonObject

        logger.info {
            "OPENROUTER_PROXY: $requestId blocking completed in ${System.currentTimeMillis() - start}ms model=$cloudModel"
        }
        return buildOllamaChunk(cloudModel, content, toolCalls, finish, done = true, usage = usage)
    }

    private fun applyOpenRouter(builder: io.ktor.http.HeadersBuilder, apiKey: String) {
        builder.append(HttpHeaders.Authorization, "Bearer $apiKey")
        builder.append("HTTP-Referer", "https://jervis.damek-soft.eu")
        builder.append("X-Title", "Jervis AI Assistant")
    }

    private fun buildOpenAiBody(body: JsonObject, cloudModel: String, stream: Boolean): JsonObject = buildJsonObject {
        val rawMessages = (body["messages"] as? JsonArray) ?: JsonArray(emptyList())
        val mappedMessages = if (rawMessages.isEmpty() && body["prompt"] is JsonPrimitive) {
            // /api/generate-shape input — lift to a chat message
            buildJsonArray {
                (body["system"] as? JsonPrimitive)?.let {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", it)
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    put("content", body["prompt"]!!)
                })
            }
        } else {
            buildJsonArray { rawMessages.forEach { add(convertMessage(it.jsonObject)) } }
        }

        put("model", cloudModel)
        put("messages", mappedMessages)
        put("stream", stream)

        val opts = body["options"] as? JsonObject
        opts?.get("temperature")?.let { put("temperature", it) }
        opts?.get("num_predict")?.let { put("max_tokens", it) }
        body["tools"]?.let { put("tools", it) }
    }

    private fun convertMessage(msg: JsonObject): JsonObject = buildJsonObject {
        for ((k, v) in msg.entries) {
            when (k) {
                "tool_calls" -> {
                    val list = (v as? JsonArray)?.map { ollamaToolCallToOpenAi(it.jsonObject) }
                        ?: emptyList()
                    put("tool_calls", JsonArray(list))
                }
                "images" -> { /* handled below */ }
                else -> put(k, v)
            }
        }
        val images = (msg["images"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (!images.isNullOrEmpty()) {
            val text = (msg["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            put("content", composeMultimodal(text, images))
        }
    }

    private fun ollamaToolCallToOpenAi(tc: JsonObject): JsonObject = buildJsonObject {
        val fn = tc["function"] as? JsonObject ?: JsonObject(emptyMap())
        val rawArgs = fn["arguments"]
        val argsStr = when (rawArgs) {
            is JsonObject, is JsonArray -> rawArgs.toString()
            is JsonPrimitive -> if (rawArgs.isString) rawArgs.content else rawArgs.toString()
            null -> ""
        }
        put("id", (tc["id"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        put("type", (tc["type"] as? JsonPrimitive)?.contentOrNull ?: "function")
        put("function", buildJsonObject {
            put("name", (fn["name"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            put("arguments", argsStr)
        })
    }

    private fun composeMultimodal(text: String, imagesB64: List<String>): JsonArray = buildJsonArray {
        if (text.isNotEmpty()) {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        }
        for (b64 in imagesB64) {
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject {
                    put("url", "data:image/jpeg;base64,$b64")
                })
            })
        }
    }

    private fun buildOllamaChunk(
        model: String,
        content: String,
        toolCalls: JsonArray?,
        finish: String?,
        done: Boolean,
        usage: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("message", buildJsonObject {
            put("role", "assistant")
            put("content", content)
            if (toolCalls != null) put("tool_calls", toolCalls)
        })
        put("done", done)
        if (finish != null) put("done_reason", finish)
        if (usage != null) {
            (usage["prompt_tokens"] as? JsonPrimitive)?.let { put("prompt_eval_count", it) }
            (usage["completion_tokens"] as? JsonPrimitive)?.let { put("eval_count", it) }
        }
    }

    fun parseRateLimitHeaders(errorText: String): Pair<Long?, String?> {
        val data = runCatching { json.parseToJsonElement(errorText).jsonObject }.getOrNull() ?: return null to null
        val err = data["error"] as? JsonObject ?: return null to null
        val headers = ((err["metadata"] as? JsonObject)?.get("headers") as? JsonObject) ?: JsonObject(emptyMap())
        val resetMs = (headers["X-RateLimit-Reset"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        val msg = ((err["message"] as? JsonPrimitive)?.contentOrNull).orEmpty().lowercase()
        val scope = listOf(
            "free-models-per-day-high-balance",
            "free-models-per-day",
            "per-day",
            "per-minute",
            "per-hour",
        ).firstOrNull { it in msg }
        return resetMs to scope
    }
}

private val JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else content
