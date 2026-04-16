package com.jervis.infrastructure.grpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.contracts.server.GetMergedRequest
import com.jervis.contracts.server.GetRequest
import com.jervis.contracts.server.GuidelinesPayload
import com.jervis.contracts.server.GuidelinesScope
import com.jervis.contracts.server.ServerGuidelinesServiceGrpcKt
import com.jervis.contracts.server.SetRequest
import com.jervis.dto.guidelines.GuidelinesUpdateRequest
import com.jervis.guidelines.GuidelinesService
import com.jervis.dto.guidelines.GuidelinesScope as DtoGuidelinesScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Component

// gRPC implementation of ServerGuidelinesService. Bodies are kotlinx-
// serialized `GuidelinesDocumentDto` / `MergedGuidelinesDto` / the input
// `GuidelinesUpdateRequest` — the server is the single source of truth
// for the schema, consumers parse on their side.
@Component
class ServerGuidelinesGrpcImpl(
    private val guidelinesService: GuidelinesService,
) : ServerGuidelinesServiceGrpcKt.ServerGuidelinesServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun getMerged(request: GetMergedRequest): GuidelinesPayload {
        val merged = guidelinesService.getMergedGuidelines(
            clientId = request.clientId.takeIf { it.isNotBlank() }?.let { ClientId.fromString(it) },
            projectId = request.projectId.takeIf { it.isNotBlank() }?.let { ProjectId.fromString(it) },
        )
        return GuidelinesPayload.newBuilder()
            .setBodyJson(json.encodeToString(merged))
            .build()
    }

    override suspend fun get(request: GetRequest): GuidelinesPayload {
        val scope = when (request.scope) {
            GuidelinesScope.GUIDELINES_SCOPE_CLIENT -> DtoGuidelinesScope.CLIENT
            GuidelinesScope.GUIDELINES_SCOPE_PROJECT -> DtoGuidelinesScope.PROJECT
            else -> DtoGuidelinesScope.GLOBAL
        }
        val doc = guidelinesService.getGuidelines(
            scope = scope,
            clientId = request.clientId.takeIf { it.isNotBlank() }?.let { ClientId.fromString(it) },
            projectId = request.projectId.takeIf { it.isNotBlank() }?.let { ProjectId.fromString(it) },
        )
        return GuidelinesPayload.newBuilder()
            .setBodyJson(json.encodeToString(doc.toDto()))
            .build()
    }

    override suspend fun set(request: SetRequest): GuidelinesPayload {
        val update = json.decodeFromString<GuidelinesUpdateRequest>(request.updateJson)
        val doc = guidelinesService.updateGuidelines(update)
        return GuidelinesPayload.newBuilder()
            .setBodyJson(json.encodeToString(doc.toDto()))
            .build()
    }
}
