package com.jervis.voice

import kotlin.math.sqrt

/**
 * Energy-based Voice Activity Detection.
 *
 * Detects speech boundaries in PCM 16-bit audio using RMS energy threshold.
 * No external dependencies — pure Kotlin math.
 *
 * State machine: IDLE → SPEECH → trailing silence → SPEECH_END → IDLE
 *
 * Whisper on VD has Silero VAD built-in for fine-grained segmentation.
 * This VAD only needs to detect utterance boundaries for the WebSocket pipeline:
 * when user stops speaking → trigger Whisper + intent processing.
 */
class EnergyVad(
    /** RMS threshold (0.0–1.0) above which audio is considered speech. */
    private val speechThreshold: Double = 0.015,
    /** Milliseconds of silence after speech to trigger SPEECH_END. */
    private val silenceTimeoutMs: Long = 700,
    /** Minimum speech duration to avoid triggering on clicks/pops. */
    private val minSpeechMs: Long = 200,
) {
    enum class State { IDLE, SPEECH, TRAILING_SILENCE }
    enum class Event { SILENCE, SPEECH_START, SPEECH_CONTINUE, SPEECH_END }

    private var state = State.IDLE
    private var speechStartMs: Long = 0
    private var silenceStartMs: Long = 0
    private var totalElapsedMs: Long = 0

    /**
     * Process a PCM 16-bit audio chunk and return a VAD event.
     *
     * @param pcm16 Raw PCM samples (16-bit signed, 16kHz mono)
     * @param chunkDurationMs Duration of this chunk in milliseconds
     * @return VAD event indicating what happened
     */
    fun onChunk(pcm16: ShortArray, chunkDurationMs: Long): Event {
        totalElapsedMs += chunkDurationMs
        val rms = calculateRms(pcm16)
        val isSpeech = rms > speechThreshold

        return when (state) {
            State.IDLE -> {
                if (isSpeech) {
                    state = State.SPEECH
                    speechStartMs = totalElapsedMs
                    Event.SPEECH_START
                } else {
                    Event.SILENCE
                }
            }

            State.SPEECH -> {
                if (isSpeech) {
                    Event.SPEECH_CONTINUE
                } else {
                    state = State.TRAILING_SILENCE
                    silenceStartMs = totalElapsedMs
                    Event.SPEECH_CONTINUE // Still in speech until silence timeout
                }
            }

            State.TRAILING_SILENCE -> {
                if (isSpeech) {
                    // Speech resumed before silence timeout
                    state = State.SPEECH
                    Event.SPEECH_CONTINUE
                } else {
                    val silenceDuration = totalElapsedMs - silenceStartMs
                    val speechDuration = silenceStartMs - speechStartMs
                    if (silenceDuration >= silenceTimeoutMs && speechDuration >= minSpeechMs) {
                        state = State.IDLE
                        Event.SPEECH_END
                    } else if (silenceDuration >= silenceTimeoutMs) {
                        // Too short to be real speech — reset
                        state = State.IDLE
                        Event.SILENCE
                    } else {
                        Event.SPEECH_CONTINUE
                    }
                }
            }
        }
    }

    /** Reset VAD state (e.g., after processing a complete utterance). */
    fun reset() {
        state = State.IDLE
        speechStartMs = 0
        silenceStartMs = 0
    }

    /** Calculate RMS (Root Mean Square) energy of PCM 16-bit samples, normalized to 0.0–1.0. */
    private fun calculateRms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in samples) {
            val normalized = sample.toDouble() / 32768.0
            sum += normalized * normalized
        }
        return sqrt(sum / samples.size)
    }
}
