package com.jervis.rpc

import com.jervis.configuration.CorrectionListRequestDto
import com.jervis.configuration.CorrectionSubmitRequestDto
import com.jervis.configuration.PythonOrchestratorClient
import com.jervis.dto.meeting.TranscriptCorrectionDto
import com.jervis.dto.meeting.TranscriptCorrectionSubmitDto
import com.jervis.service.ITranscriptCorrectionService
import mu.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class TranscriptCorrectionRpcImpl(
    private val correctionClient: com.jervis.configuration.CorrectionClient,
) : ITranscriptCorrectionService {

    override suspend fun submitCorrection(request: TranscriptCorrectionSubmitDto): TranscriptCorrectionDto {
        val result = correctionClient.submitCorrection(
            CorrectionSubmitRequestDto(
                clientId = request.clientId,
                projectId = request.projectId,
                original = request.original,
                corrected = request.corrected,
                category = request.category,
                context = request.context,
            ),
        )

        logger.info { "Submitted correction: '${request.original}' -> '${request.corrected}' (${result.sourceUrn})" }

        return TranscriptCorrectionDto(
            correctionId = result.correctionId,
            sourceUrn = result.sourceUrn,
            original = request.original,
            corrected = request.corrected,
            category = request.category,
            context = request.context,
        )
    }

    override suspend fun listCorrections(clientId: String, projectId: String?): List<TranscriptCorrectionDto> {
        val result = correctionClient.listCorrections(
            CorrectionListRequestDto(clientId = clientId, projectId = projectId),
        )

        return result.corrections.map { chunk ->
            TranscriptCorrectionDto(
                correctionId = chunk.metadata.correctionId,
                sourceUrn = chunk.sourceUrn,
                original = chunk.metadata.original,
                corrected = chunk.metadata.corrected,
                category = chunk.metadata.category,
                context = chunk.metadata.context.ifBlank { null },
            )
        }
    }

    override suspend fun deleteCorrection(sourceUrn: String): Boolean {
        return correctionClient.deleteCorrection(sourceUrn)
    }
}
