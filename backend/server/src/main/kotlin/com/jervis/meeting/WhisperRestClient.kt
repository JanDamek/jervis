package com.jervis.meeting

import com.google.protobuf.ByteString
import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.whisper.HealthRequest
import com.jervis.contracts.whisper.TranscribeEvent
import com.jervis.contracts.whisper.TranscribeOptions
import com.jervis.contracts.whisper.TranscribeRequest
import com.jervis.contracts.whisper.WhisperServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import jakarta.annotation.PreDestroy
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Client for the Whisper transcription service over gRPC.
 *
 * The wire format is fully typed — TranscribeOptions in, TranscribeEvent
 * (oneof progress | result | error) out. Audio bytes travel inline in
 * the TranscribeRequest (up to 256 MiB); multi-hour meeting audio will
 * migrate to the blob side channel when that lands.
 *
 * `baseUrl` is reinterpreted as `host[:port]` for the gRPC channel —
 * callers pass the same URL the REST client used, we strip the scheme +
 * any path and point Netty at it on port 5501.
 */
@Component
class WhisperRestClient {

    private val channels = ConcurrentHashMap<String, ManagedChannel>()

    private fun channelFor(baseUrl: String): ManagedChannel =
        channels.computeIfAbsent(baseUrl) { url ->
            val (host, port) = parseTarget(url)
            NettyChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .maxInboundMessageSize(256 * 1024 * 1024)
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(5, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build()
                .also { logger.info { "gRPC channel → $host:$port (whisper)" } }
        }

    private fun parseTarget(baseUrl: String): Pair<String, Int> {
        val normalized = if (baseUrl.contains("://")) baseUrl else "http://$baseUrl"
        val uri = URI(normalized)
        val host = uri.host ?: baseUrl.substringBefore(":")
        // REST used variable ports (8786 default); gRPC is always 5501 on
        // the whisper pod. Override at call time via `grpc.whisper.port`
        // if a different deployment moves the port.
        return host to 5501
    }

    private fun ctx(): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun isHealthy(baseUrl: String): Boolean =
        try {
            val stub = WhisperServiceGrpcKt.WhisperServiceCoroutineStub(channelFor(baseUrl))
            val resp = stub.health(HealthRequest.newBuilder().setCtx(ctx()).build())
            resp.ok
        } catch (e: Exception) {
            logger.warn(e) { "Whisper gRPC health check failed for $baseUrl" }
            false
        }

    /**
     * Send audio file to the Whisper service for transcription via
     * WhisperService.Transcribe (server-streaming). Progress events are
     * forwarded to `onProgress`; the final result is returned as a
     * [WhisperResult] (persisted to disk as JSON later by the caller).
     */
    suspend fun transcribe(
        baseUrl: String,
        audioFilePath: String,
        options: TranscribeOptions,
        onProgress: (suspend (percent: Double, segmentsDone: Int, elapsedSeconds: Double, lastSegmentText: String?) -> Unit)? = null,
    ): WhisperResult {
        val audioPath = Path.of(audioFilePath)
        val audioBytes = Files.readAllBytes(audioPath)
        val fileName = audioPath.fileName.toString()
        logger.info { "Sending audio to Whisper gRPC: $baseUrl (file=$fileName, ${audioBytes.size} bytes)" }

        val stub = WhisperServiceGrpcKt.WhisperServiceCoroutineStub(channelFor(baseUrl))
        val req = TranscribeRequest.newBuilder()
            .setCtx(ctx())
            .setAudio(ByteString.copyFrom(audioBytes))
            .setFilename(fileName)
            .setOptions(options)
            .build()

        var result: WhisperResult? = null
        try {
            stub.transcribe(req).collect { event ->
                when (event.payloadCase) {
                    TranscribeEvent.PayloadCase.PROGRESS -> {
                        val p = event.progress
                        onProgress?.invoke(
                            p.percent,
                            p.segmentsDone,
                            p.elapsedSeconds,
                            p.lastSegmentText.ifBlank { null },
                        )
                    }
                    TranscribeEvent.PayloadCase.RESULT -> {
                        result = event.result.toWhisperResult()
                    }
                    TranscribeEvent.PayloadCase.ERROR -> {
                        val err = event.error
                        result = WhisperResult(
                            text = err.text,
                            segments = emptyList(),
                            error = err.error.ifBlank { "Whisper error" },
                        )
                    }
                    TranscribeEvent.PayloadCase.PAYLOAD_NOT_SET -> {
                        logger.warn { "Whisper returned empty TranscribeEvent" }
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Whisper transcribe stream failed: ${e.message}" }
            return WhisperResult(
                text = "",
                segments = emptyList(),
                error = "Whisper gRPC error: ${e.message}",
            )
        }

        return result ?: WhisperResult(
            text = "",
            segments = emptyList(),
            error = "Whisper gRPC stream ended without result or error event",
        )
    }

    @PreDestroy
    fun shutdown() {
        channels.values.forEach {
            runCatching { it.shutdown().awaitTermination(5, TimeUnit.SECONDS) }
        }
    }
}

private fun com.jervis.contracts.whisper.ResultEvent.toWhisperResult(): WhisperResult =
    WhisperResult(
        text = text,
        segments = segmentsList.map {
            WhisperSegment(
                start = it.startSec,
                end = it.endSec,
                text = it.text,
                speaker = it.speaker.ifBlank { null },
            )
        },
        language = language.ifBlank { null },
        languageProbability = if (languageProbability > 0.0) languageProbability else null,
        duration = if (duration > 0.0) duration else null,
        textBySegment = textBySegmentMap.entries.associate { (k, v) -> k.toString() to v },
        speakers = speakersList.toList().ifEmpty { null },
        speakerEmbeddings = speakerEmbeddingsList
            .associate { it.label to it.valuesList.toList() }
            .ifEmpty { null },
    )
