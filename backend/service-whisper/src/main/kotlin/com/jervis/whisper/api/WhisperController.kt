package com.jervis.whisper.api

import com.jervis.common.client.IWhisperClient
import com.jervis.common.dto.WhisperRequestDto
import com.jervis.common.dto.WhisperResultDto
import com.jervis.whisper.domain.WhisperJob
import com.jervis.whisper.domain.WhisperService
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.rpc.krpc.ktor.server.*
import kotlinx.rpc.krpc.serialization.cbor.cbor
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class WhisperController(
    private val service: WhisperService,
    @Value("\${server.port:8080}") private val port: Int,
) : IWhisperClient {
    private val logger = KotlinLogging.logger {}

    @PostConstruct
    fun startRpcServer() {
        Thread {
            embeddedServer(Netty, port = port, host = "0.0.0.0") {
                routing {
                    rpc("/rpc") {
                        rpcConfig {
                            serialization {
                                cbor()
                            }
                        }
                        registerService<IWhisperClient> { this@WhisperController }
                    }
                }
            }.start(wait = true)
        }.start()
        logger.info { "Whisper RPC server started on port $port at /rpc" }
    }

    override suspend fun transcribe(
        @RequestBody request: WhisperRequestDto,
    ): WhisperResultDto =
        withContext(Dispatchers.IO) {
            val audioInfo =
                when (val a = request.audio) {
                    is WhisperRequestDto.Audio.Url -> "URL: ${a.url}"
                    is WhisperRequestDto.Audio.Base64 -> "Base64: mimeType=${a.mimeType}, dataSize=${a.data.length}"
                }
            logger.info {
                "Received Whisper transcription request: audio=$audioInfo, diarization=${request.diarization}"
            }

            try {
                val job: WhisperJob =
                    when (val a = request.audio) {
                        is WhisperRequestDto.Audio.Url ->
                            WhisperJob.FromUrl(
                                a.url,
                                request.diarization,
                            )

                        is WhisperRequestDto.Audio.Base64 ->
                            WhisperJob.FromBase64(
                                a.mimeType,
                                a.data,
                                request.diarization,
                            )
                    }
                val out = service.transcribe(job)
                logger.info { "Whisper transcription completed: textLength=${out.text.length}, segmentsCount=${out.segments.size}" }
                WhisperResultDto(
                    text = out.text,
                    segments = out.segments.map { WhisperResultDto.Segment(it.startSec, it.endSec, it.text) },
                )
            } catch (e: Exception) {
                logger.error(e) { "Whisper transcription failed: ${e.message}" }
                throw e
            }
        }
}
