package com.jervis.voice

import java.io.ByteArrayOutputStream

/**
 * Accumulates PCM audio and emits fixed-duration segments for Whisper.
 *
 * Whisper processes audio in chunks — sending the entire recording at once
 * is too slow for real-time. This accumulator collects PCM bytes and emits
 * a segment every [segmentDurationMs] milliseconds (default 5s).
 *
 * The caller (WebSocket handler) sends each emitted segment to Whisper GPU
 * immediately, enabling parallel transcription while the user is still speaking.
 *
 * Audio format: PCM 16-bit, 16kHz, mono (3200 bytes per 100ms chunk).
 */
class SegmentAccumulator(
    /** Duration of each segment in milliseconds. */
    private val segmentDurationMs: Long = 5000,
    /** Sample rate in Hz. */
    private val sampleRate: Int = 16000,
    /** Bits per sample. */
    private val bitsPerSample: Int = 16,
    /** Number of channels. */
    private val channels: Int = 1,
) {
    private val buffer = ByteArrayOutputStream()
    private val bytesPerMs = sampleRate * channels * (bitsPerSample / 8) / 1000

    /** Bytes needed for one complete segment. */
    private val segmentBytes = (segmentDurationMs * bytesPerMs).toInt()

    /**
     * Add PCM audio bytes to the accumulator.
     *
     * @return A complete segment (wrapped in WAV) if enough audio accumulated, null otherwise.
     */
    fun addAudio(pcm: ByteArray): ByteArray? {
        buffer.write(pcm)
        return if (buffer.size() >= segmentBytes) {
            emitSegment()
        } else {
            null
        }
    }

    /**
     * Flush remaining audio as a segment (even if shorter than segmentDurationMs).
     * Used when VAD detects SPEECH_END — send whatever is buffered.
     *
     * @return WAV-wrapped audio if buffer is non-empty, null otherwise.
     */
    fun flush(): ByteArray? {
        return if (buffer.size() > 0) {
            emitSegment()
        } else {
            null
        }
    }

    /** Reset accumulator (discard buffered audio). */
    fun reset() {
        buffer.reset()
    }

    /** Current buffered duration in milliseconds. */
    val bufferedDurationMs: Long
        get() = if (bytesPerMs > 0) buffer.size().toLong() / bytesPerMs else 0

    private fun emitSegment(): ByteArray {
        val pcm = buffer.toByteArray()
        buffer.reset()
        return wrapInWav(pcm)
    }

    /**
     * Wrap raw PCM bytes in a WAV header.
     * Whisper REST API expects WAV input.
     */
    private fun wrapInWav(pcm: ByteArray): ByteArray {
        val dataSize = pcm.size
        val headerSize = 44
        val fileSize = headerSize + dataSize
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val wav = ByteArray(fileSize)
        var offset = 0

        fun writeString(s: String) { s.toByteArray(Charsets.US_ASCII).copyInto(wav, offset); offset += s.length }
        fun writeInt(v: Int) { wav[offset] = (v and 0xFF).toByte(); wav[offset+1] = (v shr 8 and 0xFF).toByte(); wav[offset+2] = (v shr 16 and 0xFF).toByte(); wav[offset+3] = (v shr 24 and 0xFF).toByte(); offset += 4 }
        fun writeShort(v: Int) { wav[offset] = (v and 0xFF).toByte(); wav[offset+1] = (v shr 8 and 0xFF).toByte(); offset += 2 }

        writeString("RIFF")
        writeInt(fileSize - 8)
        writeString("WAVE")
        writeString("fmt ")
        writeInt(16)                // PCM format chunk size
        writeShort(1)               // PCM format
        writeShort(channels)
        writeInt(sampleRate)
        writeInt(byteRate)
        writeShort(blockAlign)
        writeShort(bitsPerSample)
        writeString("data")
        writeInt(dataSize)

        pcm.copyInto(wav, offset)
        return wav
    }
}
