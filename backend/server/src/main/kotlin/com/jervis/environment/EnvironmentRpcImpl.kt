package com.jervis.environment

import com.jervis.rpc.BaseRpcImpl
import com.jervis.common.types.ClientId
import com.jervis.common.types.EnvironmentId
import com.jervis.common.types.ProjectGroupId
import com.jervis.common.types.ProjectId
import com.jervis.dto.environment.ComponentTemplateDto
import com.jervis.dto.environment.ComponentTypeEnum
import com.jervis.dto.environment.ComponentVersionDto
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.EnvironmentStatusDto
import com.jervis.dto.environment.PortMappingDto
import com.jervis.dto.environment.PropertyMappingTemplateDto
import com.jervis.environment.toDocument
import com.jervis.environment.toDto
import com.jervis.service.environment.IEnvironmentService
import com.jervis.environment.COMPONENT_DEFAULTS
import com.jervis.environment.EnvironmentK8sService
import com.jervis.environment.PROPERTY_MAPPING_TEMPLATES
import com.jervis.environment.EnvironmentService
import com.jervis.infrastructure.error.ErrorLogService
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
class EnvironmentRpcImpl(
    private val environmentService: EnvironmentService,
    private val environmentK8sService: EnvironmentK8sService,
    errorLogService: ErrorLogService,
) : BaseRpcImpl(errorLogService),
    IEnvironmentService {

    override suspend fun getAllEnvironments(): List<EnvironmentDto> =
        executeWithErrorHandling("getAllEnvironments") {
            environmentService.getAllEnvironments().map { it.toDto() }
        }

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
            val saved = environmentService.saveEnvironment(environment.toDocument())
            // Ensure K8s namespace exists so K8s resources tab works immediately
            try {
                environmentK8sService.ensureNamespaceExists(saved.namespace)
            } catch (e: Exception) {
                logger.warn { "Failed to ensure namespace on save: ${e.message}" }
            }
            saved.toDto()
        }

    override suspend fun updateEnvironment(id: String, environment: EnvironmentDto): EnvironmentDto =
        executeWithErrorHandling("updateEnvironment") {
            val saved = environmentService.saveEnvironment(environment.copy(id = id).toDocument())
            // Ensure K8s namespace exists so K8s resources tab works immediately
            try {
                environmentK8sService.ensureNamespaceExists(saved.namespace)
            } catch (e: Exception) {
                logger.warn { "Failed to ensure namespace on update: ${e.message}" }
            }
            saved.toDto()
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

    override suspend fun getComponentTemplates(): List<ComponentTemplateDto> =
        executeWithErrorHandling("getComponentTemplates") {
            COMPONENT_DEFAULTS.map { (type, defaults) ->
                val mappingTemplates = PROPERTY_MAPPING_TEMPLATES[type]?.map {
                    PropertyMappingTemplateDto(
                        envVarName = it.envVarName,
                        valueTemplate = it.valueTemplate,
                        description = it.description,
                    )
                } ?: emptyList()
                ComponentTemplateDto(
                    type = ComponentTypeEnum.valueOf(type.name),
                    versions = defaults.versions.map { ComponentVersionDto(label = it.label, image = it.image) },
                    defaultEnvVars = defaults.defaultEnvVars,
                    defaultPorts = defaults.ports.map { PortMappingDto(it.containerPort, it.servicePort, it.name) },
                    defaultVolumeMountPath = defaults.defaultVolumeMountPath,
                    propertyMappingTemplates = mappingTemplates,
                )
            }
        }

    override suspend fun getComponentLogs(environmentId: String, componentName: String, tailLines: Int): String =
        executeWithErrorHandling("getComponentLogs") {
            environmentK8sService.getComponentLogs(EnvironmentId(ObjectId(environmentId)), componentName, tailLines)
        }

    override suspend fun syncEnvironmentResources(id: String): EnvironmentDto =
        executeWithErrorHandling("syncEnvironmentResources") {
            environmentK8sService.syncEnvironmentResources(EnvironmentId(ObjectId(id))).toDto()
        }

    override suspend fun cloneEnvironment(
        sourceId: String,
        newName: String,
        newNamespace: String,
        targetClientId: String?,
        targetGroupId: String?,
        targetProjectId: String?,
        newTier: String?,
    ): EnvironmentDto =
        executeWithErrorHandling("cloneEnvironment") {
            environmentService.cloneEnvironment(
                sourceId = EnvironmentId(ObjectId(sourceId)),
                newName = newName,
                newNamespace = newNamespace,
                targetClientId = targetClientId?.let { ClientId(ObjectId(it)) },
                targetGroupId = targetGroupId?.let { ProjectGroupId(ObjectId(it)) },
                targetProjectId = targetProjectId?.let { ProjectId(ObjectId(it)) },
                newTier = newTier?.let { com.jervis.environment.EnvironmentTier.valueOf(it.uppercase()) },
            ).toDto()
        }
}
