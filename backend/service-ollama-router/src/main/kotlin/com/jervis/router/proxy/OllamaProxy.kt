package com.jervis.router.proxy

import com.jervis.router.gpu.GpuBackend
import com.jervis.router.model.PreemptReason
import com.jervis.router.model.ProxyError
import com.jervis.router.model.RequestEnvelope
import com.jervis.router.model.RequestState
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class OllamaProxy(
    private val httpClient: HttpClient,
    private val onConnectError: (gpu: GpuBackend, error: String) -> Unit = { _, _ -> },
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Stream NDJSON chunks from an Ollama backend.
     *
     * Cancellation: Kotlin structured concurrency. When the parent scope
     * is cancelled (gRPC client disconnects, dispatcher restart, whisper
     * preemption via [RequestEnvelope.cancelToken].cancel()) the underlying
     * channel read raises CancellationException; the flow emits no more
     * items and the `finally` in the consumer fires deterministically.
     *
     * To distinguish user-cancel vs preempt-by-whisper / critical-peer,
     * the caller inspects [RequestEnvelope.state] in the catch block.
     */
    fun stream(
        backend: GpuBackend,
        envelope: RequestEnvelope,
    ): Flow<JsonObject> = flow {
        var emitted = 0
        try {
            httpClient.preparePost("${backend.url}${envelope.apiPath.path}") {
                contentType(ContentType.Application.Json)
                setBody(envelope.body.toString())
                // No request timeout — streaming generations may run for minutes.
                // Connection errors (TCP RST, EPIPE) propagate as IOException.
                timeout {
                    requestTimeoutMillis = Long.MAX_VALUE
                    socketTimeoutMillis = Long.MAX_VALUE
                }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val body = runCatching { response.bodyAsText() }.getOrDefault("")
                    throw ProxyError.UpstreamError(response.status.value, body.take(500))
                }
                val channel = response.bodyAsChannel()
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank()) continue
                    val parsed = runCatching { json.parseToJsonElement(line).jsonObjectOrNull() }
                        .getOrNull()
                    if (parsed == null) {
                        logger.debug { "PROXY_STREAM: non-JSON line ${line.take(120)}" }
                        continue
                    }
                    emit(parsed)
                    emitted++
                }
                logger.info { "PROXY_STREAM: id=${envelope.id} completed (emitted=$emitted)" }
            }
        } catch (cancel: CancellationException) {
            val state = envelope.state.get()
            when (state) {
                is RequestState.Preempted -> when (state.reason) {
                    PreemptReason.WHISPER -> {
                        logger.warn { "PROXY_STREAM: id=${envelope.id} PREEMPTED_BY_WHISPER emitted=$emitted" }
                        throw ProxyError.PreemptedByWhisper
                    }
                    PreemptReason.CRITICAL_PEER -> {
                        logger.warn { "PROXY_STREAM: id=${envelope.id} PREEMPTED_BY_CRITICAL emitted=$emitted" }
                        throw ProxyError.PreemptedByCritical
                    }
                }
                else -> {
                    logger.info { "PROXY_STREAM: id=${envelope.id} cancelled by client (emitted=$emitted)" }
                    throw cancel
                }
            }
        } catch (e: ConnectTimeoutException) {
            logger.error { "PROXY_STREAM: id=${envelope.id} connect timeout: ${e.message}" }
            onConnectError(backend, e.message ?: "connect_timeout")
            throw ProxyError.UpstreamError(0, e.message ?: "connect_timeout")
        } catch (e: java.net.ConnectException) {
            logger.error { "PROXY_STREAM: id=${envelope.id} connection failed to ${backend.url}: ${e.message}" }
            onConnectError(backend, e.message ?: "connect_error")
            throw ProxyError.UpstreamError(0, e.message ?: "connect_error")
        } catch (e: ResponseException) {
            logger.error { "PROXY_STREAM: id=${envelope.id} response exception: ${e.message}" }
            throw ProxyError.UpstreamError(e.response.status.value, e.message ?: "")
        }
    }

    /**
     * Unary forward (embeddings, /api/show). Returns the raw response JSON.
     */
    suspend fun unary(backend: GpuBackend, envelope: RequestEnvelope): JsonObject {
        try {
            val response = httpClient.post("${backend.url}${envelope.apiPath.path}") {
                contentType(ContentType.Application.Json)
                setBody(envelope.body.toString())
                timeout {
                    requestTimeoutMillis = Long.MAX_VALUE
                    socketTimeoutMillis = Long.MAX_VALUE
                }
            }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw ProxyError.UpstreamError(response.status.value, text.take(500))
            }
            return json.parseToJsonElement(text).jsonObjectOrNull()
                ?: throw ProxyError.UpstreamError(response.status.value, "non-object response")
        } catch (e: java.net.ConnectException) {
            logger.error { "PROXY_UNARY: id=${envelope.id} connection failed: ${e.message}" }
            onConnectError(backend, e.message ?: "connect_error")
            throw ProxyError.UpstreamError(0, e.message ?: "connect_error")
        }
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
    this as? JsonObject
