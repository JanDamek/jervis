package com.jervis.service.indexing.domain

import kotlinx.serialization.Serializable

/**
 * Response schema for MEETING_TRANSCRIPT_PROCESSING prompt type.
 * Matches the responseSchema defined in prompts-services.yaml for MEETING_TRANSCRIPT_PROCESSING.
 */
@Serializable
data class MeetingTranscriptProcessingResponse(
    val sentences: List<String> = emptyList(),
)
