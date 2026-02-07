package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.EnvironmentId
import com.jervis.common.types.ProjectId
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.EnvironmentStatusDto
import com.jervis.mapper.toAgentContext
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.service.IEnvironmentService
import com.jervis.service.environment.EnvironmentK8sService
import com.jervis.service.environment.EnvironmentService
import com.jervis.service.error.ErrorLogService
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
class EnvironmentRpcImpl(
    private val environmentService: EnvironmentService,
    private val environmentK8sService: EnvironmentK8sService,
    errorLogService: ErrorLogService,
) : BaseRpcImpl(errorLogService),
    IEnvironmentService {

    override suspend fun listEnvironments(clientId: String): List<EnvironmentDto> =
        executeWithErrorHandling("listEnvironments") {
            environmentService.listEnvironmentsForClient(ClientId(ObjectId(clientId))).map { it.toDto() }
        }

    override suspend fun getEnvironment(id: String): EnvironmentDto =
        executeWithErrorHandling("getEnvironment") {
            environmentService.getEnvironmentById(EnvironmentId(ObjectId(id))).toDto()
        }

    override suspend fun saveEnvironment(environment: EnvironmentDto): EnvironmentDto =
        executeWithErrorHandling("saveEnvironment") {
            environmentService.saveEnvironment(environment.toDocument()).toDto()
        }

    override suspend fun updateEnvironment(id: String, environment: EnvironmentDto): EnvironmentDto =
        executeWithErrorHandling("updateEnvironment") {
            environmentService.saveEnvironment(environment.copy(id = id).toDocument()).toDto()
        }

    override suspend fun deleteEnvironment(id: String) {
        executeWithErrorHandling("deleteEnvironment") {
            environmentService.deleteEnvironment(EnvironmentId(ObjectId(id)))
        }
    }

    override suspend fun provisionEnvironment(id: String): EnvironmentDto =
        executeWithErrorHandling("provisionEnvironment") {
            environmentK8sService.provisionEnvironment(EnvironmentId(ObjectId(id))).toDto()
        }

    override suspend fun deprovisionEnvironment(id: String): EnvironmentDto =
        executeWithErrorHandling("deprovisionEnvironment") {
            environmentK8sService.deprovisionEnvironment(EnvironmentId(ObjectId(id))).toDto()
        }

    override suspend fun getEnvironmentStatus(id: String): EnvironmentStatusDto =
        executeWithErrorHandling("getEnvironmentStatus") {
            environmentK8sService.getEnvironmentStatus(EnvironmentId(ObjectId(id)))
        }

    override suspend fun resolveEnvironmentForProject(projectId: String): EnvironmentDto? =
        executeWithErrorHandling("resolveEnvironmentForProject") {
            environmentService.resolveEnvironmentForProject(ProjectId(ObjectId(projectId)))?.toDto()
        }
}
