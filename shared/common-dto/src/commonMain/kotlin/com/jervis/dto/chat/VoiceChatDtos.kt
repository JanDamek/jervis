package com.jervis.dto.chat

import kotlinx.serialization.Serializable

/**
 * Audio chunk sent from client during voice recording.
 * Streamed via kRPC Flow<VoiceAudioChunk>.
 */
@Serializable
data class VoiceAudioChunk(
    /** Raw PCM/WAV audio bytes, base64 encoded for kRPC transport. */
    val data: String,
    /** True when this is the last chunk (user stopped speaking). */
    val isLast: Boolean = false,
)

/**
 * Event streamed back from server during voice chat processing.
 * Flow: TRANSCRIBING → TRANSCRIBED → RESPONDING → RESPONSE_TOKEN → TTS_AUDIO → DONE
 */
@Serializable
data class VoiceChatEvent(
    val type: VoiceChatEventType,
    /** Text content (transcription, response token, error message). */
    val text: String = "",
    /** TTS audio data, base64 encoded WAV. Only for TTS_AUDIO events. */
    val audioData: String = "",
)

@Serializable
enum class VoiceChatEventType {
    /** Whisper is processing audio. */
    TRANSCRIBING,
    /** Transcription complete — text contains the transcribed speech. */
    TRANSCRIBED,
    /** Orchestrator is generating response. */
    RESPONDING,
    /** Single response token from orchestrator. */
    RESPONSE_TOKEN,
    /** TTS audio chunk (base64 WAV). */
    TTS_AUDIO,
    /** Processing complete. */
    DONE,
    /** Error occurred. */
    ERROR,
}
