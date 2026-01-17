package com.jervis.whisper.service

import com.jervis.common.client.IWhisperClient
import com.jervis.common.dto.WhisperRequestDto
import com.jervis.common.dto.WhisperResultDto
import com.jervis.common.dto.WhisperSegmentDto
import com.jervis.whisper.domain.WhisperJob
import com.jervis.whisper.domain.WhisperService
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class WhisperServiceImpl(
    private val whisperService: WhisperService
) : IWhisperClient {

    override suspend fun transcribe(request: WhisperRequestDto): WhisperResultDto {
        return try {
            logger.info { "Received Whisper transcribe request" }

            val job = when {
                request.audioUrl != null -> WhisperJob.FromUrl(
                    url = request.audioUrl,
                    diarization = request.diarization
                )
                request.audioBase64 != null && request.mimeType != null -> WhisperJob.FromBase64(
                    mimeType = request.mimeType,
                    data = request.audioBase64,
                    diarization = request.diarization
                )
                else -> throw IllegalArgumentException("Either audioUrl or audioBase64 with mimeType must be provided")
            }

            val transcript = whisperService.transcribe(job)

            val segments = transcript.segments.map { seg ->
                WhisperSegmentDto(
                    startSec = seg.startSec,
                    endSec = seg.endSec,
                    text = seg.text
                )
            }

            logger.info { "Whisper transcription completed: textLength=${transcript.text.length}, segments=${segments.size}" }

            WhisperResultDto(
                text = transcript.text,
                segments = segments,
                success = true,
                errorMessage = null
            )
        } catch (e: Exception) {
            logger.error(e) { "Whisper transcription failed: ${e.message}" }
            WhisperResultDto(
                text = "",
                segments = emptyList(),
                success = false,
                errorMessage = e.message
            )
        }
    }
}
