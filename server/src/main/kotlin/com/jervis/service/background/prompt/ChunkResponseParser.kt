package com.jervis.service.background.prompt

import com.jervis.domain.background.ArtifactType
import com.jervis.domain.background.BackgroundArtifact
import com.jervis.domain.background.Checkpoint
import com.jervis.domain.background.SourceRef
import com.jervis.domain.background.SourceRefType
import com.jervis.service.background.executor.ChunkResult
import com.jervis.service.background.executor.NextAction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.bson.types.ObjectId
import java.security.MessageDigest

/**
 * Parses JSON responses from background chunk LLM calls.
 */
object ChunkResponseParser {
    private val logger = KotlinLogging.logger {}
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun parse(
        taskId: ObjectId,
        responseText: String,
    ): ChunkResult {
        try {
            val cleanedJson = extractJsonFromResponse(responseText)
            val response = json.decodeFromString<ChunkResponse>(cleanedJson)

            val artifacts =
                response.artifacts.map { artifactDto ->
                    val payloadMap = jsonObjectToMap(artifactDto.payload)
                    BackgroundArtifact(
                        taskId = taskId,
                        type = ArtifactType.valueOf(artifactDto.type),
                        payload = payloadMap,
                        sourceRef = parseSourceRef(artifactDto.payload),
                        contentHash = calculateContentHash(payloadMap),
                        confidence = artifactDto.confidence,
                    )
                }

            val checkpoint = parseCheckpoint(response.newCheckpoint)

            val nextAction =
                when {
                    response.next_actions.contains("STOP") -> NextAction.STOP
                    response.next_actions.contains("REQUEST_MORE_CONTEXT") -> NextAction.REQUEST_MORE_CONTEXT
                    else -> NextAction.CONTINUE
                }

            return ChunkResult(
                artifacts = artifacts,
                checkpoint = checkpoint,
                progressDelta = response.progressDelta,
                nextAction = nextAction,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse chunk response: $responseText" }
            throw IllegalArgumentException("Invalid chunk response JSON", e)
        }
    }

    private fun extractJsonFromResponse(response: String): String {
        val jsonStart = response.indexOf('{')
        val jsonEnd = response.lastIndexOf('}')

        return if (jsonStart >= 0 && jsonEnd > jsonStart) {
            response.substring(jsonStart, jsonEnd + 1)
        } else {
            response
        }
    }

    private fun parseSourceRef(payload: JsonObject): SourceRef =
        SourceRef(
            type = SourceRefType.valueOf(payload["sourceRefType"]?.jsonPrimitive?.contentOrNull ?: "DOC"),
            id = payload["sourceRefId"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            offset = payload["offset"]?.jsonPrimitive?.intOrNull,
            lineStart = payload["lineStart"]?.jsonPrimitive?.intOrNull,
            lineEnd = payload["lineEnd"]?.jsonPrimitive?.intOrNull,
        )

    private fun jsonObjectToMap(jsonObject: JsonObject): Map<String, Any> =
        jsonObject.mapValues { (_, value) ->
            jsonElementToAny(value)
        }

    private fun jsonElementToAny(element: JsonElement): Any =
        when (element) {
            is JsonPrimitive -> {
                element.booleanOrNull
                    ?: element.intOrNull
                    ?: element.longOrNull
                    ?: element.doubleOrNull
                    ?: element.contentOrNull
                    ?: ""
            }

            is JsonArray -> element.map { jsonElementToAny(it) }
            is JsonObject -> element.mapValues { (_, v) -> jsonElementToAny(v) }
        }

    private fun parseCheckpoint(checkpointDto: CheckpointDto?): Checkpoint? {
        if (checkpointDto == null) return null

        return when (checkpointDto.kind) {
            "DocumentScan" ->
                Checkpoint.DocumentScan(
                    documentId = checkpointDto.data["documentId"]?.jsonPrimitive?.contentOrNull ?: "",
                    lastOffset = checkpointDto.data["lastOffset"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalChunks = checkpointDto.data["totalChunks"]?.jsonPrimitive?.intOrNull ?: 0,
                )

            "CodeAnalysis" ->
                Checkpoint.CodeAnalysis(
                    lastFile = checkpointDto.data["lastFile"]?.jsonPrimitive?.contentOrNull,
                    remainingFiles =
                        checkpointDto.data["remainingFiles"]
                            ?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                )

            "ThreadClustering" ->
                Checkpoint.ThreadClustering(
                    processedThreadIds =
                        checkpointDto.data["processedThreadIds"]
                            ?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                            ?.toSet() ?: emptySet(),
                    state =
                        checkpointDto.data["state"]
                            ?.jsonObject
                            ?.mapValues { (_, v) ->
                                v.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
                            } ?: emptyMap(),
                )

            else ->
                Checkpoint.Generic(
                    cursor = checkpointDto.data["cursor"]?.jsonPrimitive?.contentOrNull,
                    notes = checkpointDto.data["notes"]?.jsonPrimitive?.contentOrNull,
                )
        }
    }

    private fun calculateContentHash(payload: Map<String, Any>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val payloadString = payload.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }
        val hash = digest.digest(payloadString.toByteArray())
        return "sha256:" + hash.joinToString("") { "%02x".format(it) }
    }

    @Serializable
    private data class ChunkResponse(
        val artifacts: List<ArtifactDto>,
        val progressDelta: Double,
        val newCheckpoint: CheckpointDto?,
        val next_actions: List<String>,
    )

    @Serializable
    private data class ArtifactDto(
        val type: String,
        val payload: JsonObject,
        val confidence: Double,
    )

    @Serializable
    private data class CheckpointDto(
        val kind: String,
        val data: JsonObject,
    )
}
