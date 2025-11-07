package com.jervis.service.indexing

import com.jervis.common.client.IWhisperClient
import com.jervis.common.dto.WhisperResultDto
import com.jervis.configuration.properties.AudioMonitoringProperties
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * Minimal audio transcript indexing service placeholder.
 * Accepts uploaded audio files and logs that a transcription job was enqueued.
 * Actual transcription and RAG indexing can be implemented later and wired here.
 * Service for indexing audio files by transcribing them and storing transcript embeddings.
 * Minimal audio transcript indexing facade.
 *
 * This service provides a narrow API used by AudioMonitoringService to trigger (re)indexing.
 * For now it only scans for supported audio files and logs counts, acting as an integration point
 * where full audio-to-text processing (e.g., Whisper) can be plugged in later.
 * Service for indexing audio files by transcribing them into text.
 * Minimal implementation to integrate with existing monitoring and indexing pipeline.
 */
@Service
class AudioTranscriptIndexingService(
    private val whisperClient: IWhisperClient,
    private val audioMonitoringProps: AudioMonitoringProperties,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
        private const val UNKNOWN = "unknown"
        private const val MIN_SENTENCE_LENGTH = 10
    }

    data class AudioIndexingResult(
        val processedFiles: Int,
        val skippedFiles: Int,
        val errorFiles: Int,
        val totalTranscriptionTimeSeconds: Long,
        val processedAudios: Int,
        val skippedAudios: Int,
        val errorAudios: Int,
    )

    data class AudioMetadata(
        val fileName: String,
        val filePath: Path,
        val source: String,
        val format: String,
        val durationSeconds: Float? = null,
        val language: String? = null,
    )

    private enum class Scope(
        val label: String,
    ) {
        PROJECT("project"),
        CLIENT("client"),
    }

    private fun findSupportedAudioFiles(root: Path): List<Path> {
        if (!Files.exists(root)) return emptyList()
        val regex =
            Regex(
                ".*\\.(" + audioMonitoringProps.supportedFormats.joinToString("|") { Regex.escape(it) } + ")$",
                RegexOption.IGNORE_CASE,
            )
        return Files
            .walk(root)
            .filter { it.isRegularFile() }
            .filter { it.toString().matches(regex) }
            .toList()
    }

    private fun createTranscriptSentences(
        transcription: WhisperResultDto,
        metadata: AudioMetadata,
    ): List<String> {
        val sentences = mutableListOf<String>()

        sentences.add(
            buildString {
                append("Audio file '")
                append(metadata.fileName)
                append("' in ")
                append(metadata.format)
                append(" format")
                metadata.durationSeconds?.let { append(", duration: ${it.toInt()}s") }
                metadata.language?.let { append(", language: $it") }
            },
        )

        if (transcription.segments.isNotEmpty()) {
            transcription.segments.forEach { segment ->
                val text = segment.text.trim()
                if (text.isNotEmpty() && text.length >= MIN_SENTENCE_LENGTH) {
                    sentences.add(text)
                }
            }
        } else {
            val textSentences =
                transcription.text
                    .split(Regex("[.!?\\n]+"))
                    .map { it.trim() }
                    .filter { it.length >= MIN_SENTENCE_LENGTH }
            sentences.addAll(textSentences)
        }

        return sentences.filter { it.isNotEmpty() }
    }
}
