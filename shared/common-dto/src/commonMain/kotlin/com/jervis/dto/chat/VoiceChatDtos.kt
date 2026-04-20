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

/**
 * TTS streaming event for IChatService.streamTts — used by chat bubbles' play button.
 *
 * Flow: HEADER (once, with sampleRate) → PCM (many, base64-encoded raw PCM) → DONE.
 * ERROR is emitted terminally on failure.
 */
@Serializable
data class TtsChunkEvent(
    val type: TtsChunkEventType,
    /** Only for HEADER — the PCM sample rate of subsequent chunks. */
    val sampleRate: Int = 0,
    /** Only for PCM — base64-encoded raw 16-bit LE PCM audio. */
    val audioData: String = "",
    /** Only for ERROR — human-readable error detail. */
    val errorMessage: String = "",
)

@Serializable
enum class TtsChunkEventType { HEADER, PCM, DONE, ERROR }

/**
 * Siri / Google Assistant one-shot text query.
 * Used by mobile VoiceQueryActivity, wearApp VoiceQueryActivity, and (via KMP bridge) iosApp.
 */
@Serializable
data class SiriQueryResponse(
    val response: String,
    val taskId: String? = null,
    val state: String? = null,
)

/**
 * Configuration for a live voice session (Meeting live assist + helper hints).
 * Client opens the session, streams chunks, collects events.
 */
@Serializable
data class VoiceSessionConfig(
    val source: String = "app_chat",
    val tts: Boolean = true,
    val liveAssist: Boolean = false,
    val meetingId: String? = null,
    val wearableNotify: Boolean = false,
    val helperEnabled: Boolean = false,
)

/**
 * One audio chunk in a voice session stream (or terminal signal with isLast=true).
 */
@Serializable
data class VoiceSessionChunk(
    /** Base64-encoded WAV or PCM. */
    val audioData: String = "",
    val chunkIndex: Int = 0,
    val isLast: Boolean = false,
)

/**
 * Event emitted from server during a live voice session.
 */
@Serializable
data class VoiceSessionEvent(
    val type: VoiceSessionEventType,
    val sessionId: String = "",
    /** Transcription text, hint text, response token, or error message. */
    val text: String = "",
    /** Base64 TTS audio (only for TTS_AUDIO). */
    val audioData: String = "",
)

@Serializable
enum class VoiceSessionEventType {
    SESSION_STARTED,
    CHUNK_TRANSCRIBED,
    HINT,
    TOKEN,
    RESPONSE,
    TTS_AUDIO,
    DONE,
    ERROR,
}
