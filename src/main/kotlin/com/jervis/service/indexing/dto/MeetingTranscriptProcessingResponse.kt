package com.jervis.service.indexing.dto

import kotlinx.serialization.Serializable

/**
 * Response schema for MEETING_TRANSCRIPT_PROCESSING prompt type.
 * Matches the responseSchema defined in prompts.yaml for MEETING_TRANSCRIPT_PROCESSING.
 */
@Serializable
data class MeetingTranscriptProcessingResponse(
    val sentences: List<String> = emptyList()
)