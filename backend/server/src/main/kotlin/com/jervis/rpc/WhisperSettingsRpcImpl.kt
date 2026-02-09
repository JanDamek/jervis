package com.jervis.rpc

import com.jervis.dto.whisper.WhisperModelSize
import com.jervis.dto.whisper.WhisperSettingsDto
import com.jervis.dto.whisper.WhisperSettingsUpdateDto
import com.jervis.dto.whisper.WhisperTask
import com.jervis.entity.WhisperSettingsDocument
import com.jervis.repository.WhisperSettingsRepository
import com.jervis.service.IWhisperSettingsService
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class WhisperSettingsRpcImpl(
    private val repository: WhisperSettingsRepository,
) : IWhisperSettingsService {
    private val logger = KotlinLogging.logger {}

    override suspend fun getSettings(): WhisperSettingsDto {
        val doc = repository.findById(WhisperSettingsDocument.SINGLETON_ID) ?: WhisperSettingsDocument()
        return doc.toDto()
    }

    override suspend fun updateSettings(request: WhisperSettingsUpdateDto): WhisperSettingsDto {
        val existing = repository.findById(WhisperSettingsDocument.SINGLETON_ID) ?: WhisperSettingsDocument()

        val updated = existing.copy(
            model = request.model?.toModelString() ?: existing.model,
            task = request.task?.toTaskString() ?: existing.task,
            language = when {
                request.clearLanguage -> null
                request.language != null -> request.language
                else -> existing.language
            },
            beamSize = (request.beamSize ?: existing.beamSize).coerceIn(1, 10),
            vadFilter = request.vadFilter ?: existing.vadFilter,
            wordTimestamps = request.wordTimestamps ?: existing.wordTimestamps,
            initialPrompt = when {
                request.clearInitialPrompt -> null
                request.initialPrompt != null -> request.initialPrompt
                else -> existing.initialPrompt
            },
            conditionOnPreviousText = request.conditionOnPreviousText ?: existing.conditionOnPreviousText,
            noSpeechThreshold = (request.noSpeechThreshold ?: existing.noSpeechThreshold).coerceIn(0.0, 1.0),
            maxParallelJobs = (request.maxParallelJobs ?: existing.maxParallelJobs).coerceIn(1, 10),
            timeoutMultiplier = (request.timeoutMultiplier ?: existing.timeoutMultiplier).coerceIn(1, 10),
            minTimeoutSeconds = (request.minTimeoutSeconds ?: existing.minTimeoutSeconds).coerceIn(60, 3600),
        )

        repository.save(updated)
        logger.info { "Whisper settings updated: model=${updated.model}, task=${updated.task}, lang=${updated.language}, parallel=${updated.maxParallelJobs}" }

        return updated.toDto()
    }

    /**
     * Get current settings document for internal use (WhisperJobRunner, MeetingContinuousIndexer).
     */
    suspend fun getSettingsDocument(): WhisperSettingsDocument {
        return repository.findById(WhisperSettingsDocument.SINGLETON_ID) ?: WhisperSettingsDocument()
    }

    private fun WhisperSettingsDocument.toDto() = WhisperSettingsDto(
        model = WhisperModelSize.entries.find { it.toModelString() == model } ?: WhisperModelSize.BASE,
        task = if (task == "translate") WhisperTask.TRANSLATE else WhisperTask.TRANSCRIBE,
        language = language,
        beamSize = beamSize,
        vadFilter = vadFilter,
        wordTimestamps = wordTimestamps,
        initialPrompt = initialPrompt,
        conditionOnPreviousText = conditionOnPreviousText,
        noSpeechThreshold = noSpeechThreshold,
        maxParallelJobs = maxParallelJobs,
        timeoutMultiplier = timeoutMultiplier,
        minTimeoutSeconds = minTimeoutSeconds,
    )

    private fun WhisperModelSize.toModelString() = when (this) {
        WhisperModelSize.TINY -> "tiny"
        WhisperModelSize.BASE -> "base"
        WhisperModelSize.SMALL -> "small"
        WhisperModelSize.MEDIUM -> "medium"
        WhisperModelSize.LARGE_V3 -> "large-v3"
    }

    private fun WhisperTask.toTaskString() = when (this) {
        WhisperTask.TRANSCRIBE -> "transcribe"
        WhisperTask.TRANSLATE -> "translate"
    }
}
