package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.EnvironmentId
import com.jervis.common.types.ProjectId
import com.jervis.dto.environment.ComponentTemplateDto
import com.jervis.dto.environment.ComponentTypeEnum
import com.jervis.dto.environment.ComponentVersionDto
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.EnvironmentStatusDto
import com.jervis.dto.environment.ExecResultDto
import com.jervis.dto.environment.FileUploadResultDto
import com.jervis.dto.environment.PortMappingDto
import com.jervis.dto.environment.PropertyMappingTemplateDto
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.service.IEnvironmentService
import com.jervis.service.environment.COMPONENT_DEFAULTS
import com.jervis.service.environment.EnvironmentK8sService
import com.jervis.service.environment.PROPERTY_MAPPING_TEMPLATES
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

    override suspend fun syncEnvironmentResources(id: String): EnvironmentDto =
        executeWithErrorHandling("syncEnvironmentResources") {
            environmentK8sService.syncEnvironmentResources(EnvironmentId(ObjectId(id))).toDto()
        }

    override suspend fun uploadFileToComponent(
        id: String,
        componentName: String,
        fileName: String,
        fileBase64: String,
        targetDir: String,
    ): FileUploadResultDto =
        executeWithErrorHandling("uploadFileToComponent") {
            val env = environmentService.getEnvironmentById(EnvironmentId(ObjectId(id)))
            val component = env.components.find { it.name == componentName || it.id == componentName }
                ?: throw IllegalArgumentException("Component '$componentName' not found")

            val fileBytes = java.util.Base64.getDecoder().decode(fileBase64)

            val targetPath = environmentK8sService.uploadFileToPod(
                namespace = env.namespace,
                componentName = component.name,
                fileBytes = fileBytes,
                fileName = fileName,
                targetDir = targetDir,
            )

            FileUploadResultDto(
                targetPath = targetPath,
                sizeBytes = fileBytes.size.toLong(),
                componentName = component.name,
            )
        }

    override suspend fun execInComponent(
        id: String,
        componentName: String,
        command: List<String>,
    ): ExecResultDto =
        executeWithErrorHandling("execInComponent") {
            val env = environmentService.getEnvironmentById(EnvironmentId(ObjectId(id)))
            val component = env.components.find { it.name == componentName || it.id == componentName }
                ?: throw IllegalArgumentException("Component '$componentName' not found")

            val output = environmentK8sService.execInPod(
                namespace = env.namespace,
                componentName = component.name,
                command = command,
            )

            ExecResultDto(
                output = output,
                componentName = component.name,
            )
        }
}
