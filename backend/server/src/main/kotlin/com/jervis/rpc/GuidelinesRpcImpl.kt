package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.guidelines.GuidelinesDocumentDto
import com.jervis.dto.guidelines.GuidelinesScope
import com.jervis.dto.guidelines.GuidelinesUpdateRequest
import com.jervis.dto.guidelines.MergedGuidelinesDto
import com.jervis.service.IGuidelinesService
import com.jervis.service.guidelines.GuidelinesService
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class GuidelinesRpcImpl(
    private val guidelinesService: GuidelinesService,
) : IGuidelinesService {
    private val logger = KotlinLogging.logger {}

    override suspend fun getGuidelines(
        scope: String,
        clientId: String?,
        projectId: String?,
    ): GuidelinesDocumentDto {
        val guidelinesScope = GuidelinesScope.valueOf(scope)
        val doc = guidelinesService.getGuidelines(
            scope = guidelinesScope,
            clientId = clientId?.let { ClientId(it) },
            projectId = projectId?.let { ProjectId(it) },
        )
        return doc.toDto()
    }

    override suspend fun updateGuidelines(request: GuidelinesUpdateRequest): GuidelinesDocumentDto {
        val doc = guidelinesService.updateGuidelines(request)
        return doc.toDto()
    }

    override suspend fun getMergedGuidelines(
        clientId: String?,
        projectId: String?,
    ): MergedGuidelinesDto {
        return guidelinesService.getMergedGuidelines(
            clientId = clientId?.let { ClientId(it) },
            projectId = projectId?.let { ProjectId(it) },
        )
    }

    override suspend fun deleteGuidelines(
        scope: String,
        clientId: String?,
        projectId: String?,
    ): Boolean {
        val guidelinesScope = GuidelinesScope.valueOf(scope)
        return guidelinesService.deleteGuidelines(
            scope = guidelinesScope,
            clientId = clientId?.let { ClientId(it) },
            projectId = projectId?.let { ProjectId(it) },
        )
    }
}
