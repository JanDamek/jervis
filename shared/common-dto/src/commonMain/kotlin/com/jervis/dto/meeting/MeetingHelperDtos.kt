package com.jervis.dto.meeting

import kotlinx.serialization.Serializable

/**
 * DTOs for Meeting Helper — real-time translation + suggestions
 * pushed to a separate device during active recording.
 */

@Serializable
enum class HelperMessageType {
    TRANSLATION,
    SUGGESTION,
    QUESTION_PREDICT,
    STATUS,
    /** General visual analysis from IP camera (scene overview, layout). */
    VISUAL_INSIGHT,
    /** Whiteboard / paper OCR — text extraction with structure preserved. */
    WHITEBOARD_OCR,
    /** Monitor / screen OCR — code-aware text extraction. */
    SCREEN_OCR,
    /** Live meeting transcript chunk (speaker + text). Rolled in the Assistant view, not shown as a bubble. */
    TRANSCRIPT,
}

@Serializable
data class HelperMessageDto(
    val type: HelperMessageType,
    val text: String,
    val context: String = "",
    val fromLang: String = "",
    val toLang: String = "",
    val timestamp: String = "",
)

@Serializable
data class HelperSessionStartDto(
    val meetingId: String,
    // deviceId is informational only — assistant hints are broadcast to every
    // device of the user via the RPC event stream, so there is no reason to
    // pick a specific target device. Kept on the DTO for legacy callers.
    val deviceId: String = "",
    val sourceLang: String = "en",
    val targetLang: String = "cs",
)

@Serializable
data class HelperSessionDto(
    val meetingId: String,
    val deviceId: String,
    val sourceLang: String,
    val targetLang: String,
    val active: Boolean,
)

@Serializable
data class DeviceInfoDto(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val platform: String,
    val capabilities: List<String> = emptyList(),
    val lastSeen: String = "",
)
