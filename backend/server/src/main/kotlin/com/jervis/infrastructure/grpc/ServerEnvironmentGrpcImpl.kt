package com.jervis.infrastructure.grpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.EnvironmentId
import com.jervis.common.types.ProjectGroupId
import com.jervis.common.types.ProjectId
import com.jervis.contracts.server.AddComponentRequest
import com.jervis.contracts.server.AddPropertyMappingRequest
import com.jervis.contracts.server.AutoSuggestPropertyMappingsResponse
import com.jervis.contracts.server.CloneEnvironmentRequest
import com.jervis.contracts.server.ComponentLink as ComponentLinkProto
import com.jervis.contracts.server.ComponentStatus as ComponentStatusProto
import com.jervis.contracts.server.ComponentTemplate as ComponentTemplateProto
import com.jervis.contracts.server.ComponentTemplateList
import com.jervis.contracts.server.ComponentVersion as ComponentVersionProto
import com.jervis.contracts.server.ConfigureComponentRequest
import com.jervis.contracts.server.CreateEnvironmentRequest
import com.jervis.contracts.server.DeleteEnvironmentRequest
import com.jervis.contracts.server.DeleteEnvironmentResponse
import com.jervis.contracts.server.DiscoverNamespaceRequest
import com.jervis.contracts.server.Environment as EnvironmentProto
import com.jervis.contracts.server.EnvironmentComponent as EnvironmentComponentProto
import com.jervis.contracts.server.EnvironmentIdRequest
import com.jervis.contracts.server.EnvironmentList
import com.jervis.contracts.server.EnvironmentStatus as EnvironmentStatusProto
import com.jervis.contracts.server.GetEnvironmentRequest
import com.jervis.contracts.server.ListComponentTemplatesRequest
import com.jervis.contracts.server.ListEnvironmentsRequest
import com.jervis.contracts.server.PortMapping as PortMappingProto
import com.jervis.contracts.server.PropertyMapping as PropertyMappingProto
import com.jervis.contracts.server.PropertyMappingTemplate as PropertyMappingTemplateProto
import com.jervis.contracts.server.ReplicateEnvironmentRequest
import com.jervis.contracts.server.ServerEnvironmentServiceGrpcKt
import com.jervis.dto.environment.ComponentLinkDto
import com.jervis.dto.environment.ComponentStatusDto
import com.jervis.dto.environment.EnvironmentComponentDto
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.EnvironmentStatusDto
import com.jervis.dto.environment.PortMappingDto
import com.jervis.dto.environment.PropertyMappingDto
import com.jervis.environment.COMPONENT_DEFAULTS
import com.jervis.environment.ComponentType
import com.jervis.environment.EnvironmentComponent
import com.jervis.environment.EnvironmentDocument
import com.jervis.environment.EnvironmentK8sService
import com.jervis.environment.EnvironmentService
import com.jervis.environment.EnvironmentState
import com.jervis.environment.EnvironmentTier
import com.jervis.environment.PROPERTY_MAPPING_TEMPLATES
import com.jervis.environment.PropertyMapping
import com.jervis.environment.toDto
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
class ServerEnvironmentGrpcImpl(
    private val environmentService: EnvironmentService,
    private val environmentK8sService: EnvironmentK8sService,
) : ServerEnvironmentServiceGrpcKt.ServerEnvironmentServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun listEnvironments(request: ListEnvironmentsRequest): EnvironmentList {
        val envs = if (request.clientId.isNotBlank()) {
            environmentService.listEnvironmentsForClient(ClientId(ObjectId(request.clientId)))
        } else {
            environmentService.getAllEnvironments()
        }
        return EnvironmentList.newBuilder()
            .addAllItems(envs.map { it.toDto().toProto() })
            .build()
    }

    override suspend fun getEnvironment(request: GetEnvironmentRequest): EnvironmentProto =
        environmentService.getEnvironmentById(EnvironmentId(ObjectId(request.environmentId)))
            .toDto().toProto()

    override suspend fun createEnvironment(request: CreateEnvironmentRequest): EnvironmentProto {
        val tier = request.tier.takeIf { it.isNotBlank() }
            ?.let { EnvironmentTier.valueOf(it.uppercase()) } ?: EnvironmentTier.DEV
        val env = EnvironmentDocument(
            clientId = ClientId(ObjectId(request.clientId)),
            name = request.name,
            namespace = request.namespace.takeIf { it.isNotBlank() }
                ?: request.name.lowercase().replace(Regex("[^a-z0-9-]"), "-"),
            tier = tier,
            description = request.description.takeIf { it.isNotBlank() },
            agentInstructions = request.agentInstructions.takeIf { it.isNotBlank() },
            storageSizeGi = request.storageSizeGi.takeIf { it > 0 } ?: 5,
        )
        return environmentService.saveEnvironment(env).toDto().toProto()
    }

    override suspend fun deleteEnvironment(request: DeleteEnvironmentRequest): DeleteEnvironmentResponse {
        val envId = EnvironmentId(ObjectId(request.environmentId))
        val env = environmentService.getEnvironmentByIdOrNull(envId)
        if (env != null && env.state == EnvironmentState.RUNNING) {
            try {
                environmentK8sService.deprovisionEnvironment(env.id, deleteNamespace = true)
            } catch (deprovisionError: Exception) {
                logger.warn(deprovisionError) { "Failed to deprovision before delete, continuing" }
            }
        }
        environmentService.deleteEnvironment(envId)
        return DeleteEnvironmentResponse.newBuilder().setOk(true).build()
    }

    override suspend fun addComponent(request: AddComponentRequest): EnvironmentProto {
        val envId = EnvironmentId(ObjectId(request.environmentId))
        val env = environmentService.getEnvironmentById(envId)
        val componentType = ComponentType.valueOf(request.type.uppercase())
        val defaults = COMPONENT_DEFAULTS[componentType]
        val explicitImage = request.image.takeIf { it.isNotBlank() }
        val explicitVersion = request.version.takeIf { it.isNotBlank() }
        val image = explicitImage ?: if (explicitVersion != null && defaults != null) {
            defaults.versions.find { it.label.contains(explicitVersion, ignoreCase = true) }?.image
                ?: defaults.image
        } else defaults?.image
        val ports = defaults?.ports ?: emptyList()
        val envVars = (defaults?.defaultEnvVars ?: emptyMap()) + request.envVarsMap
        val volumeMountPath = defaults?.defaultVolumeMountPath
        val componentId = request.name.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        val startOrder = if (request.startOrderAuto || request.startOrder < 0) {
            env.components.size * 10
        } else request.startOrder
        val component = EnvironmentComponent(
            id = componentId,
            name = request.name,
            type = componentType,
            image = image,
            ports = ports,
            envVars = envVars,
            volumeMountPath = volumeMountPath,
            sourceRepo = request.sourceRepo.takeIf { it.isNotBlank() },
            sourceBranch = request.sourceBranch.takeIf { it.isNotBlank() },
            dockerfilePath = request.dockerfilePath.takeIf { it.isNotBlank() },
            startOrder = startOrder,
        )
        return environmentService.saveEnvironment(env.copy(components = env.components + component))
            .toDto().toProto()
    }

    override suspend fun configureComponent(request: ConfigureComponentRequest): EnvironmentProto {
        val envId = EnvironmentId(ObjectId(request.environmentId))
        val env = environmentService.getEnvironmentById(envId)
        val componentIndex = env.components.indexOfFirst {
            it.name == request.componentName || it.id == request.componentName
        }
        require(componentIndex >= 0) { "Component '${request.componentName}' not found" }
        val existing = env.components[componentIndex]
        val updated = existing.copy(
            image = request.image.takeIf { it.isNotBlank() } ?: existing.image,
            envVars = if (request.hasEnvVars || request.envVarsMap.isNotEmpty()) {
                existing.envVars + request.envVarsMap
            } else existing.envVars,
            cpuLimit = request.cpuLimit.takeIf { it.isNotBlank() } ?: existing.cpuLimit,
            memoryLimit = request.memoryLimit.takeIf { it.isNotBlank() } ?: existing.memoryLimit,
            sourceRepo = request.sourceRepo.takeIf { it.isNotBlank() } ?: existing.sourceRepo,
            sourceBranch = request.sourceBranch.takeIf { it.isNotBlank() } ?: existing.sourceBranch,
            dockerfilePath = request.dockerfilePath.takeIf { it.isNotBlank() } ?: existing.dockerfilePath,
        )
        val updatedComponents = env.components.toMutableList().apply { set(componentIndex, updated) }
        return environmentService.saveEnvironment(env.copy(components = updatedComponents))
            .toDto().toProto()
    }

    override suspend fun deployEnvironment(request: EnvironmentIdRequest): EnvironmentProto =
        environmentK8sService.provisionEnvironment(EnvironmentId(ObjectId(request.environmentId)))
            .toDto().toProto()

    override suspend fun stopEnvironment(request: EnvironmentIdRequest): EnvironmentProto =
        environmentK8sService.deprovisionEnvironment(EnvironmentId(ObjectId(request.environmentId)))
            .toDto().toProto()

    override suspend fun syncEnvironment(request: EnvironmentIdRequest): EnvironmentProto =
        environmentK8sService.syncEnvironmentResources(EnvironmentId(ObjectId(request.environmentId)))
            .toDto().toProto()

    override suspend fun getEnvironmentStatus(request: EnvironmentIdRequest): EnvironmentStatusProto {
        val status = environmentK8sService.getEnvironmentStatus(EnvironmentId(ObjectId(request.environmentId)))
        return status.toProto()
    }

    override suspend fun cloneEnvironment(request: CloneEnvironmentRequest): EnvironmentProto {
        val newNamespace = request.newNamespace.takeIf { it.isNotBlank() }
            ?: request.newName.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        val newTier = request.newTier.takeIf { it.isNotBlank() }
            ?.let { EnvironmentTier.valueOf(it.uppercase()) }
        return environmentService.cloneEnvironment(
            sourceId = EnvironmentId(ObjectId(request.environmentId)),
            newName = request.newName,
            newNamespace = newNamespace,
            targetClientId = request.targetClientId.takeIf { it.isNotBlank() }?.let { ClientId(ObjectId(it)) },
            targetGroupId = request.targetGroupId.takeIf { it.isNotBlank() }?.let { ProjectGroupId(ObjectId(it)) },
            targetProjectId = request.targetProjectId.takeIf { it.isNotBlank() }?.let { ProjectId(ObjectId(it)) },
            newTier = newTier,
        ).toDto().toProto()
    }

    override suspend fun addPropertyMapping(request: AddPropertyMappingRequest): EnvironmentProto {
        val envId = EnvironmentId(ObjectId(request.environmentId))
        val env = environmentService.getEnvironmentById(envId)
        val projectComp = env.components.find { it.id == request.projectComponentId }
            ?: error("Project component '${request.projectComponentId}' not found")
        env.components.find { it.id == request.targetComponentId }
            ?: error("Target component '${request.targetComponentId}' not found")
        val exists = env.propertyMappings.any {
            it.projectComponentId == request.projectComponentId && it.propertyName == request.propertyName
        }
        require(!exists) {
            "Mapping for '${request.propertyName}' on '${projectComp.name}' already exists"
        }
        val mapping = PropertyMapping(
            projectComponentId = request.projectComponentId,
            propertyName = request.propertyName,
            targetComponentId = request.targetComponentId,
            valueTemplate = request.valueTemplate,
        )
        return environmentService.saveEnvironment(env.copy(propertyMappings = env.propertyMappings + mapping))
            .toDto().toProto()
    }

    override suspend fun autoSuggestPropertyMappings(
        request: EnvironmentIdRequest,
    ): AutoSuggestPropertyMappingsResponse {
        val envId = EnvironmentId(ObjectId(request.environmentId))
        val env = environmentService.getEnvironmentById(envId)
        val projects = env.components.filter { it.type == ComponentType.PROJECT }
        val infra = env.components.filter { it.type != ComponentType.PROJECT }
        var newMappings = env.propertyMappings.toList()
        var addedCount = 0
        for (project in projects) {
            for (infraComp in infra) {
                val templates = PROPERTY_MAPPING_TEMPLATES[infraComp.type] ?: continue
                for (tmpl in templates) {
                    val exists = newMappings.any {
                        it.projectComponentId == project.id && it.propertyName == tmpl.envVarName
                    }
                    if (exists) continue
                    newMappings = newMappings + PropertyMapping(
                        projectComponentId = project.id,
                        propertyName = tmpl.envVarName,
                        targetComponentId = infraComp.id,
                        valueTemplate = tmpl.valueTemplate,
                    )
                    addedCount++
                }
            }
        }
        val saved = environmentService.saveEnvironment(env.copy(propertyMappings = newMappings))
        return AutoSuggestPropertyMappingsResponse.newBuilder()
            .setEnvironment(saved.toDto().toProto())
            .setMappingsAdded(addedCount)
            .build()
    }

    override suspend fun discoverNamespace(request: DiscoverNamespaceRequest): EnvironmentProto {
        val tier = request.tier.takeIf { it.isNotBlank() }
            ?.let { EnvironmentTier.valueOf(it.uppercase()) } ?: EnvironmentTier.DEV
        return environmentK8sService.discoverFromNamespace(
            namespace = request.namespace,
            clientId = ClientId(ObjectId(request.clientId)),
            name = request.name.takeIf { it.isNotBlank() },
            tier = tier,
        ).toDto().toProto()
    }

    override suspend fun replicateEnvironment(request: ReplicateEnvironmentRequest): EnvironmentProto {
        val newNamespace = request.newNamespace.takeIf { it.isNotBlank() }
            ?: request.newName.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        val newTier = request.newTier.takeIf { it.isNotBlank() }
            ?.let { EnvironmentTier.valueOf(it.uppercase()) }
        val cloned = environmentService.cloneEnvironment(
            sourceId = EnvironmentId(ObjectId(request.environmentId)),
            newName = request.newName,
            newNamespace = newNamespace,
            targetClientId = request.targetClientId.takeIf { it.isNotBlank() }?.let { ClientId(ObjectId(it)) },
            newTier = newTier,
        )
        return environmentK8sService.provisionEnvironment(cloned.id).toDto().toProto()
    }

    override suspend fun syncFromK8s(request: EnvironmentIdRequest): EnvironmentProto =
        environmentK8sService.syncFromK8s(EnvironmentId(ObjectId(request.environmentId)))
            .toDto().toProto()

    override suspend fun listComponentTemplates(
        request: ListComponentTemplatesRequest,
    ): ComponentTemplateList {
        val builder = ComponentTemplateList.newBuilder()
        COMPONENT_DEFAULTS.forEach { (type, defaults) ->
            val tmplBuilder = ComponentTemplateProto.newBuilder()
                .setType(type.name)
                .addAllVersions(defaults.versions.map {
                    ComponentVersionProto.newBuilder()
                        .setLabel(it.label)
                        .setImage(it.image)
                        .build()
                })
                .addAllDefaultPorts(defaults.ports.map { it.toDto().toProto() })
                .putAllDefaultEnvVars(defaults.defaultEnvVars)
                .setDefaultVolumeMountPath(defaults.defaultVolumeMountPath ?: "")
            PROPERTY_MAPPING_TEMPLATES[type]?.forEach {
                tmplBuilder.addPropertyMappingTemplates(
                    PropertyMappingTemplateProto.newBuilder()
                        .setEnvVarName(it.envVarName)
                        .setValueTemplate(it.valueTemplate)
                        .setDescription(it.description)
                        .build(),
                )
            }
            builder.addItems(tmplBuilder.build())
        }
        return builder.build()
    }
}

// ── DTO → proto converters ──────────────────────────────────────────────

private fun EnvironmentDto.toProto(): EnvironmentProto {
    val builder = EnvironmentProto.newBuilder()
        .setId(id)
        .setClientId(clientId)
        .setGroupId(groupId ?: "")
        .setProjectId(projectId ?: "")
        .setName(name)
        .setDescription(description ?: "")
        .setTier(tier.name)
        .setNamespace(namespace)
        .setAgentInstructions(agentInstructions ?: "")
        .setState(state.name)
        .setStorageSizeGi(storageSizeGi)
    components.forEach { builder.addComponents(it.toProto()) }
    componentLinks.forEach { builder.addComponentLinks(it.toProto()) }
    propertyMappings.forEach { builder.addPropertyMappings(it.toProto()) }
    yamlManifests.forEach { (k, v) -> builder.putYamlManifests(k, v) }
    return builder.build()
}

private fun EnvironmentComponentDto.toProto(): EnvironmentComponentProto {
    val builder = EnvironmentComponentProto.newBuilder()
        .setId(id)
        .setName(name)
        .setType(type.name)
        .setImage(image ?: "")
        .setProjectId(projectId ?: "")
        .setCpuLimit(cpuLimit ?: "")
        .setMemoryLimit(memoryLimit ?: "")
        .setAutoStart(autoStart)
        .setStartOrder(startOrder)
        .setHealthCheckPath(healthCheckPath ?: "")
        .setVolumeMountPath(volumeMountPath ?: "")
        .setSourceRepo(sourceRepo ?: "")
        .setSourceBranch(sourceBranch ?: "")
        .setDockerfilePath(dockerfilePath ?: "")
        .setDeploymentYaml(deploymentYaml ?: "")
        .setServiceYaml(serviceYaml ?: "")
        .setComponentState(componentState.name)
    ports.forEach { builder.addPorts(it.toProto()) }
    envVars.forEach { (k, v) -> builder.putEnvVars(k, v) }
    configMapData.forEach { (k, v) -> builder.putConfigMapData(k, v) }
    return builder.build()
}

private fun PortMappingDto.toProto(): PortMappingProto =
    PortMappingProto.newBuilder()
        .setContainerPort(containerPort)
        .setServicePort(servicePort ?: 0)
        .setName(name)
        .build()

private fun ComponentLinkDto.toProto(): ComponentLinkProto =
    ComponentLinkProto.newBuilder()
        .setSourceComponentId(sourceComponentId)
        .setTargetComponentId(targetComponentId)
        .setDescription(description)
        .build()

private fun PropertyMappingDto.toProto(): PropertyMappingProto =
    PropertyMappingProto.newBuilder()
        .setProjectComponentId(projectComponentId)
        .setPropertyName(propertyName)
        .setTargetComponentId(targetComponentId)
        .setValueTemplate(valueTemplate)
        .setResolvedValue(resolvedValue ?: "")
        .build()

private fun EnvironmentStatusDto.toProto(): EnvironmentStatusProto =
    EnvironmentStatusProto.newBuilder()
        .setEnvironmentId(environmentId)
        .setNamespace(namespace)
        .setState(state.name)
        .addAllComponentStatuses(componentStatuses.map { it.toProto() })
        .build()

private fun ComponentStatusDto.toProto(): ComponentStatusProto =
    ComponentStatusProto.newBuilder()
        .setComponentId(componentId)
        .setName(name)
        .setReady(ready)
        .setReplicas(replicas)
        .setAvailableReplicas(availableReplicas)
        .setMessage(message ?: "")
        .build()

