package com.jervis.infrastructure.llm

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.correction.AnswerCorrectionsRequest
import com.jervis.contracts.correction.AnswerCorrectionsResponse
import com.jervis.contracts.correction.CorrectResult
import com.jervis.contracts.correction.CorrectTargetedRequest
import com.jervis.contracts.correction.CorrectTranscriptRequest
import com.jervis.contracts.correction.CorrectWithInstructionRequest
import com.jervis.contracts.correction.CorrectWithInstructionResponse
import com.jervis.contracts.correction.CorrectionRule
import com.jervis.contracts.correction.CorrectionSegment
import com.jervis.contracts.correction.CorrectionServiceGrpcKt
import com.jervis.contracts.correction.DeleteCorrectionRequest
import com.jervis.contracts.correction.ListCorrectionsRequest
import com.jervis.contracts.correction.ListCorrectionsResponse
import com.jervis.contracts.correction.SubmitCorrectionRequest
import com.jervis.contracts.correction.SubmitCorrectionResponse
import com.jervis.infrastructure.grpc.GrpcChannels
import io.grpc.ManagedChannel
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Component
class CorrectionClient(
    @Qualifier(GrpcChannels.CORRECTION_CHANNEL) channel: ManagedChannel,
) {
    private val stub = CorrectionServiceGrpcKt.CorrectionServiceCoroutineStub(channel)

    private fun ctx(): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun submitCorrection(
        clientId: String,
        projectId: String?,
        original: String,
        corrected: String,
        category: String = "general",
        context: String? = null,
    ): SubmitCorrectionResponse {
        logger.info { "CORRECTION_SUBMIT: '$original' -> '$corrected'" }
        return stub.submitCorrection(
            SubmitCorrectionRequest.newBuilder()
                .setCtx(ctx())
                .setClientId(clientId)
                .setProjectId(projectId ?: "")
                .setOriginal(original)
                .setCorrected(corrected)
                .setCategory(category)
                .setContext(context ?: "")
                .build(),
        )
    }

    suspend fun correctTranscript(
        clientId: String,
        projectId: String?,
        meetingId: String?,
        segments: List<CorrectionSegment>,
        chunkSize: Int = 20,
        speakerHints: Map<String, String> = emptyMap(),
    ): CorrectResult {
        logger.info { "CORRECTION_CORRECT: ${segments.size} segments" }
        return stub.correctTranscript(
            CorrectTranscriptRequest.newBuilder()
                .setCtx(ctx())
                .setClientId(clientId)
                .setProjectId(projectId ?: "")
                .setMeetingId(meetingId ?: "")
                .addAllSegments(segments)
                .setChunkSize(chunkSize)
                .putAllSpeakerHints(speakerHints)
                .build(),
        )
    }

    suspend fun listCorrections(
        clientId: String,
        projectId: String?,
        maxResults: Int = 100,
    ): ListCorrectionsResponse {
        logger.info { "CORRECTION_LIST: clientId=$clientId" }
        return stub.listCorrections(
            ListCorrectionsRequest.newBuilder()
                .setCtx(ctx())
                .setClientId(clientId)
                .setProjectId(projectId ?: "")
                .setMaxResults(maxResults)
                .build(),
        )
    }

    suspend fun answerCorrectionQuestions(
        clientId: String,
        projectId: String?,
        answers: List<CorrectionRule>,
    ): Boolean {
        logger.info { "CORRECTION_ANSWER: ${answers.size} answers" }
        return try {
            val resp: AnswerCorrectionsResponse = stub.answerCorrectionQuestions(
                AnswerCorrectionsRequest.newBuilder()
                    .setCtx(ctx())
                    .setClientId(clientId)
                    .setProjectId(projectId ?: "")
                    .addAllAnswers(answers)
                    .build(),
            )
            resp.status == "success"
        } catch (e: Exception) {
            logger.error { "CORRECTION_ANSWER_FAIL: ${e.message}" }
            false
        }
    }

    suspend fun correctWithInstruction(
        clientId: String,
        projectId: String?,
        segments: List<CorrectionSegment>,
        instruction: String,
    ): CorrectWithInstructionResponse {
        logger.info { "CORRECTION_INSTRUCT: ${segments.size} segments, instruction='${instruction.take(80)}'" }
        return stub.correctWithInstruction(
            CorrectWithInstructionRequest.newBuilder()
                .setCtx(ctx())
                .setClientId(clientId)
                .setProjectId(projectId ?: "")
                .addAllSegments(segments)
                .setInstruction(instruction)
                .build(),
        )
    }

    suspend fun correctTargeted(
        clientId: String,
        projectId: String?,
        meetingId: String?,
        segments: List<CorrectionSegment>,
        retranscribedIndices: List<Int>,
        userCorrectedIndices: Map<String, String> = emptyMap(),
    ): CorrectResult {
        logger.info { "CORRECTION_TARGETED: ${segments.size} segments, ${retranscribedIndices.size} retranscribed" }
        return stub.correctTargeted(
            CorrectTargetedRequest.newBuilder()
                .setCtx(ctx())
                .setClientId(clientId)
                .setProjectId(projectId ?: "")
                .setMeetingId(meetingId ?: "")
                .addAllSegments(segments)
                .addAllRetranscribedIndices(retranscribedIndices)
                .putAllUserCorrectedIndices(userCorrectedIndices)
                .build(),
        )
    }

    suspend fun deleteCorrection(sourceUrn: String): Boolean {
        logger.info { "CORRECTION_DELETE: $sourceUrn" }
        return try {
            val resp = stub.deleteCorrection(
                DeleteCorrectionRequest.newBuilder()
                    .setCtx(ctx())
                    .setSourceUrn(sourceUrn)
                    .build(),
            )
            resp.status == "success"
        } catch (e: Exception) {
            logger.error { "CORRECTION_DELETE_FAIL: $sourceUrn ${e.message}" }
            false
        }
    }
}
