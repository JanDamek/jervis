package com.jervis.service.coding

import com.jervis.types.ClientId
import com.jervis.types.ProjectId

/**
 * Common interface for coding engines (Aider, OpenHands, ...).
 * New engines can be added by implementing this interface and wiring into the gateway tool.
 */
interface CodingEngine {
    suspend fun execute(request: CodingRequest): CodingResult
}

data class CodingRequest(
    val correlationId: String,
    val clientId: ClientId,
    val projectId: ProjectId?,
    val taskDescription: String,
    val targetFiles: List<String> = emptyList(),
    val extra: Map<String, String> = emptyMap(),
)

data class CodingResult(
    val success: Boolean,
    val summary: String,
    val details: String? = null,
    val engine: String,
    val metadata: Map<String, String> = emptyMap(),
)
