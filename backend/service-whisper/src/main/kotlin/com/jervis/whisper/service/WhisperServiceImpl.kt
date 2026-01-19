package com.jervis.whisper.service

import com.jervis.common.client.IWhisperClient
import com.jervis.common.dto.WhisperRequestDto
import com.jervis.common.dto.WhisperResultDto
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

            val job = when (val audio = request.audio) {
                is WhisperRequestDto.Audio.Url -> WhisperJob.FromUrl(
                    url = audio.url,
                    diarization = request.diarization
                )
                is WhisperRequestDto.Audio.Base64 -> WhisperJob.FromBase64(
                    mimeType = audio.mimeType,
                    data = audio.data,
                    diarization = request.diarization
                )
            }

            val transcript = whisperService.transcribe(job)

            val segments = transcript.segments.map { seg ->
                WhisperResultDto.Segment(
                    startSec = seg.startSec,
                    endSec = seg.endSec,
                    text = seg.text
                )
            }

            logger.info { "Whisper transcription completed: textLength=${transcript.text.length}, segments=${segments.size}" }

            WhisperResultDto(
                text = transcript.text,
                segments = segments
            )
        } catch (e: Exception) {
            logger.error(e) { "Whisper transcription failed: ${e.message}" }
            WhisperResultDto(
                text = "",
                segments = emptyList()
            )
        }
    }
}
