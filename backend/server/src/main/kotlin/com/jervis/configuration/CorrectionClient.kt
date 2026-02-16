package com.jervis.configuration

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * REST client for the Correction Service.
 *
 * Separated from orchestrator to:
 * - Keep orchestrator focused on task decomposition and coding workflows
 * - Allow independent scaling and deployment of correction service
 * - Enable parallel development by separate teams
 *
 * This service handles transcript correction using KB-stored rules + Ollama GPU.
 */
class CorrectionClient(baseUrl: String) {

    private val apiBaseUrl = baseUrl.trimEnd('/')

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = true
                },
            )
        }
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = 30_000   // 30s connect timeout only
            socketTimeoutMillis = Long.MAX_VALUE
        }
    }

    /**
     * Submit a correction rule to the correction agent.
     * Stores it in KB as a chunk with kind="transcript_correction".
     */
    suspend fun submitCorrection(request: CorrectionSubmitRequestDto): CorrectionSubmitResultDto {
        logger.info { "CORRECTION_SUBMIT: '${request.original}' -> '${request.corrected}'" }
        return client.post("$apiBaseUrl/correction/submit") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /**
     * Correct transcript segments using KB-stored corrections + Ollama GPU.
     */
    suspend fun correctTranscript(request: CorrectionRequestDto): CorrectionResultDto {
        logger.info { "CORRECTION_CORRECT: ${request.segments.size} segments" }
        return client.post("$apiBaseUrl/correction/correct") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /**
     * List all stored corrections for a client/project.
     */
    suspend fun listCorrections(request: CorrectionListRequestDto): CorrectionListResultDto {
        logger.info { "CORRECTION_LIST: clientId=${request.clientId}" }
        return client.post("$apiBaseUrl/correction/list") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /**
     * Submit user answers to correction questions as new KB correction rules.
     */
    suspend fun answerCorrectionQuestions(request: CorrectionAnswerRequestDto): Boolean {
        logger.info { "CORRECTION_ANSWER: ${request.answers.size} answers" }
        return try {
            client.post("$apiBaseUrl/correction/answer") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            true
        } catch (e: Exception) {
            logger.error { "CORRECTION_ANSWER_FAIL: ${e.message}" }
            false
        }
    }

    /**
     * Re-correct transcript based on user's natural language instruction.
     */
    suspend fun correctWithInstruction(request: CorrectionInstructRequestDto): CorrectionInstructResultDto {
        logger.info { "CORRECTION_INSTRUCT: ${request.segments.size} segments, instruction='${request.instruction.take(80)}'" }
        return client.post("$apiBaseUrl/correction/instruct") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /**
     * Run targeted correction on retranscribed + user-corrected segments.
     */
    suspend fun correctTargeted(request: CorrectionTargetedRequestDto): CorrectionResultDto {
        logger.info { "CORRECTION_TARGETED: ${request.segments.size} segments, ${request.retranscribedIndices.size} retranscribed" }
        return client.post("$apiBaseUrl/correction/correct-targeted") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /**
     * Delete a correction rule from KB.
     */
    suspend fun deleteCorrection(sourceUrn: String): Boolean {
        logger.info { "CORRECTION_DELETE: $sourceUrn" }
        return try {
            client.post("$apiBaseUrl/correction/delete") {
                contentType(ContentType.Application.Json)
                setBody(CorrectionDeleteRequestDto(sourceUrn))
            }
            true
        } catch (e: Exception) {
            logger.error { "CORRECTION_DELETE_FAIL: $sourceUrn ${e.message}" }
            false
        }
    }
}

// --- Correction Agent DTOs ---

@kotlinx.serialization.Serializable
data class CorrectionSubmitRequestDto(
    @kotlinx.serialization.SerialName("clientId") val clientId: String,
    @kotlinx.serialization.SerialName("projectId") val projectId: String? = null,
    val original: String,
    val corrected: String,
    val category: String = "general",
    val context: String? = null,
)

@kotlinx.serialization.Serializable
data class CorrectionSubmitResultDto(
    @kotlinx.serialization.SerialName("correctionId") val correctionId: String = "",
    @kotlinx.serialization.SerialName("sourceUrn") val sourceUrn: String = "",
    val status: String = "",
)

@kotlinx.serialization.Serializable
data class CorrectionRequestDto(
    @kotlinx.serialization.SerialName("clientId") val clientId: String,
    @kotlinx.serialization.SerialName("projectId") val projectId: String? = null,
    @kotlinx.serialization.SerialName("meetingId") val meetingId: String? = null,
    val segments: List<CorrectionSegmentDto>,
    @kotlinx.serialization.SerialName("chunkSize") val chunkSize: Int = 20,
)

@kotlinx.serialization.Serializable
data class CorrectionSegmentDto(
    val i: Int,
    @kotlinx.serialization.SerialName("startSec") val startSec: Double,
    @kotlinx.serialization.SerialName("endSec") val endSec: Double,
    val text: String,
    val speaker: String? = null,
)

@kotlinx.serialization.Serializable
data class CorrectionResultDto(
    val segments: List<CorrectionSegmentDto>,
    val questions: List<CorrectionQuestionPythonDto> = emptyList(),
    val status: String,
)

@kotlinx.serialization.Serializable
data class CorrectionQuestionPythonDto(
    val id: String,
    val i: Int,
    val original: String,
    val question: String,
    val options: List<String> = emptyList(),
    val context: String? = null,
)

@kotlinx.serialization.Serializable
data class CorrectionAnswerRequestDto(
    @kotlinx.serialization.SerialName("clientId") val clientId: String,
    @kotlinx.serialization.SerialName("projectId") val projectId: String? = null,
    val answers: List<CorrectionAnswerItemDto>,
)

@kotlinx.serialization.Serializable
data class CorrectionAnswerItemDto(
    val original: String,
    val corrected: String,
    val category: String = "general",
    val context: String? = null,
)

@kotlinx.serialization.Serializable
data class CorrectionListRequestDto(
    @kotlinx.serialization.SerialName("clientId") val clientId: String,
    @kotlinx.serialization.SerialName("projectId") val projectId: String? = null,
    @kotlinx.serialization.SerialName("maxResults") val maxResults: Int = 100,
)

@kotlinx.serialization.Serializable
data class CorrectionListResultDto(
    val corrections: List<CorrectionChunkDto> = emptyList(),
)

@kotlinx.serialization.Serializable
data class CorrectionChunkDto(
    val content: String = "",
    @kotlinx.serialization.SerialName("sourceUrn") val sourceUrn: String = "",
    val metadata: CorrectionChunkMetadataDto = CorrectionChunkMetadataDto(),
)

@kotlinx.serialization.Serializable
data class CorrectionChunkMetadataDto(
    val original: String = "",
    val corrected: String = "",
    val category: String = "general",
    val context: String = "",
    @kotlinx.serialization.SerialName("correctionId") val correctionId: String = "",
)

@kotlinx.serialization.Serializable
data class CorrectionDeleteRequestDto(
    @kotlinx.serialization.SerialName("sourceUrn") val sourceUrn: String,
)

@kotlinx.serialization.Serializable
data class CorrectionTargetedRequestDto(
    @kotlinx.serialization.SerialName("clientId") val clientId: String,
    @kotlinx.serialization.SerialName("projectId") val projectId: String? = null,
    @kotlinx.serialization.SerialName("meetingId") val meetingId: String? = null,
    val segments: List<CorrectionSegmentDto>,
    @kotlinx.serialization.SerialName("retranscribedIndices") val retranscribedIndices: List<Int>,
    @kotlinx.serialization.SerialName("userCorrectedIndices") val userCorrectedIndices: Map<String, String> = emptyMap(),
)

@kotlinx.serialization.Serializable
data class CorrectionInstructRequestDto(
    @kotlinx.serialization.SerialName("clientId") val clientId: String,
    @kotlinx.serialization.SerialName("projectId") val projectId: String? = null,
    val segments: List<CorrectionSegmentDto>,
    val instruction: String,
)

@kotlinx.serialization.Serializable
data class CorrectionInstructResultDto(
    val segments: List<CorrectionSegmentDto> = emptyList(),
    @kotlinx.serialization.SerialName("newRules") val newRules: List<CorrectionInstructRuleDto> = emptyList(),
    val status: String,
    val summary: String? = null,
)

@kotlinx.serialization.Serializable
data class CorrectionInstructRuleDto(
    @kotlinx.serialization.SerialName("correctionId") val correctionId: String = "",
    @kotlinx.serialization.SerialName("sourceUrn") val sourceUrn: String = "",
    val status: String = "",
)
