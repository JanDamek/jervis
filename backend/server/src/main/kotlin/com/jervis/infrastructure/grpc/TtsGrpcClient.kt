package com.jervis.infrastructure.grpc

import com.google.protobuf.ByteString
import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.tts.AudioChunk
import com.jervis.contracts.tts.SpeakRequest
import com.jervis.contracts.tts.SpeakResponse
import com.jervis.contracts.tts.TtsServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.flow.Flow
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

// Kotlin-side client for jervis.tts.TtsService. Dedicated channel so the
// synthesis traffic stays off the orchestrator/KB channels. 64 MiB cap
// covers long WAV responses.
@Component
class TtsGrpcClient(
    @Value("\${grpc.tts.host:jervis-tts}") private val ttsHost: String,
    @Value("\${grpc.tts.port:5501}") private val ttsPort: Int,
) {
    private val channel: ManagedChannel = run {
        val maxMsgBytes = 64 * 1024 * 1024
        NettyChannelBuilder.forAddress(ttsHost, ttsPort)
            .usePlaintext()
            .maxInboundMessageSize(maxMsgBytes)
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()
            .also { logger.info { "gRPC channel → $ttsHost:$ttsPort (tts)" } }
    }

    private val stub = TtsServiceGrpcKt.TtsServiceCoroutineStub(channel)

    private fun ctx(): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun speak(text: String, speed: Double = 1.0, language: String = ""): ByteArray {
        val resp: SpeakResponse = stub.speak(
            SpeakRequest.newBuilder()
                .setCtx(ctx())
                .setText(text)
                .setSpeed(speed)
                .setLanguage(language)
                .build(),
        )
        return resp.wav.toByteArray()
    }

    fun speakStream(text: String, speed: Double = 1.0, language: String = ""): Flow<AudioChunk> =
        stub.speakStream(
            SpeakRequest.newBuilder()
                .setCtx(ctx())
                .setText(text)
                .setSpeed(speed)
                .setLanguage(language)
                .build(),
        )

    @PreDestroy
    fun shutdown() {
        runCatching { channel.shutdown().awaitTermination(5, TimeUnit.SECONDS) }
    }
}
