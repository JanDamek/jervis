package com.jervis.service

import com.jervis.dto.whisper.WhisperSettingsDto
import com.jervis.dto.whisper.WhisperSettingsUpdateDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IWhisperSettingsService {
    /** Get current Whisper settings (returns defaults if never saved). */
    suspend fun getSettings(): WhisperSettingsDto

    /** Partially update Whisper settings. Only non-null fields are applied. */
    suspend fun updateSettings(request: WhisperSettingsUpdateDto): WhisperSettingsDto
}
