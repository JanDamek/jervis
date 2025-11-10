package com.jervis.whisper.domain

data class Segment(
    val startSec: Double,
    val endSec: Double,
    val text: String,
)

data class Transcript(
    val text: String,
    val segments: List<Segment> = emptyList(),
)

sealed interface WhisperJob {
    val diarization: Boolean

    data class FromUrl(
        val url: String,
        override val diarization: Boolean,
    ) : WhisperJob

    data class FromBase64(
        val mimeType: String,
        val data: String,
        override val diarization: Boolean,
    ) : WhisperJob
}

interface WhisperService {
    suspend fun transcribe(job: WhisperJob): Transcript
}
