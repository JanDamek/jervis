package com.jervis.service

import com.jervis.dto.meeting.TranscriptCorrectionDto
import com.jervis.dto.meeting.TranscriptCorrectionSubmitDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface ITranscriptCorrectionService {

    /** Submit a new correction rule (stored in KB via Python orchestrator). */
    suspend fun submitCorrection(request: TranscriptCorrectionSubmitDto): TranscriptCorrectionDto

    /** List all correction rules for a client/project. */
    suspend fun listCorrections(clientId: String, projectId: String?): List<TranscriptCorrectionDto>

    /** Delete a correction rule by sourceUrn. */
    suspend fun deleteCorrection(sourceUrn: String): Boolean
}
