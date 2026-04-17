package com.jervis.infrastructure.grpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.EnvironmentId
import com.jervis.common.types.ProjectGroupId
import com.jervis.common.types.ProjectId
import com.jervis.contracts.server.AddComponentRequest
import com.jervis.contracts.server.AddPropertyMappingRequest
import com.jervis.contracts.server.AutoSuggestPropertyMappingsResponse
import com.jervis.contracts.server.CloneEnvironmentRequest
import com.jervis.contracts.server.ConfigureComponentRequest
import com.jervis.contracts.server.CreateEnvironmentRequest
import com.jervis.contracts.server.DeleteEnvironmentRequest
import com.jervis.contracts.server.DeleteEnvironmentResponse
import com.jervis.contracts.server.DiscoverNamespaceRequest
import com.jervis.contracts.server.EnvironmentIdRequest
import com.jervis.contracts.server.EnvironmentListResponse
import com.jervis.contracts.server.EnvironmentResponse
import com.jervis.contracts.server.EnvironmentStatusResponse
import com.jervis.contracts.server.GetEnvironmentRequest
import com.jervis.contracts.server.ListComponentTemplatesRequest
import com.jervis.contracts.server.ListComponentTemplatesResponse
import com.jervis.contracts.server.ListEnvironmentsRequest
import com.jervis.contracts.server.ReplicateEnvironmentRequest
import com.jervis.contracts.server.ServerEnvironmentServiceGrpcKt
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.EnvironmentStatusDto
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
class ServerEnvironmentGrpcImpl(
    private val environmentService: EnvironmentService,
    private val environmentK8sService: EnvironmentK8sService,
) : ServerEnvironmentServiceGrpcKt.ServerEnvironmentServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    override suspend fun listEnvironments(request: ListEnvironmentsRequest): EnvironmentListResponse {
        val envs = if (request.clientId.isNotBlank()) {
            environmentService.listEnvironmentsForClient(ClientId(ObjectId(request.clientId)))
        } else {
            environmentService.getAllEnvironments()
        }
        val dtos = envs.map { it.toDto() }
        return EnvironmentListResponse.newBuilder()
            .setItemsJson(json.encodeToString(ListSerializer(EnvironmentDto.serializer()), dtos))
            .build()
    }

    override suspend fun getEnvironment(request: GetEnvironmentRequest): EnvironmentResponse {
        val env = environmentService.getEnvironmentById(EnvironmentId(ObjectId(request.environmentId)))
        return env.toEnvironmentResponse()
    }

    override suspend fun createEnvironment(request: CreateEnvironmentRequest): EnvironmentResponse {
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
        val saved = environmentService.saveEnvironment(env)
        return saved.toEnvironmentResponse()
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

    override suspend fun addComponent(request: AddComponentRequest): EnvironmentResponse {
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
        val saved = environmentService.saveEnvironment(env.copy(components = env.components + component))
        return saved.toEnvironmentResponse()
    }

    override suspend fun configureComponent(request: ConfigureComponentRequest): EnvironmentResponse {
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
        val saved = environmentService.saveEnvironment(env.copy(components = updatedComponents))
        return saved.toEnvironmentResponse()
    }

    override suspend fun deployEnvironment(request: EnvironmentIdRequest): EnvironmentResponse {
        val env = environmentK8sService.provisionEnvironment(EnvironmentId(ObjectId(request.environmentId)))
        return env.toEnvironmentResponse()
    }

    override suspend fun stopEnvironment(request: EnvironmentIdRequest): EnvironmentResponse {
        val env = environmentK8sService.deprovisionEnvironment(EnvironmentId(ObjectId(request.environmentId)))
        return env.toEnvironmentResponse()
    }

    override suspend fun syncEnvironment(request: EnvironmentIdRequest): EnvironmentResponse {
        val env = environmentK8sService.syncEnvironmentResources(EnvironmentId(ObjectId(request.environmentId)))
        return env.toEnvironmentResponse()
    }

    override suspend fun getEnvironmentStatus(request: EnvironmentIdRequest): EnvironmentStatusResponse {
        val status = environmentK8sService.getEnvironmentStatus(EnvironmentId(ObjectId(request.environmentId)))
        return EnvironmentStatusResponse.newBuilder()
            .setBodyJson(json.encodeToString(EnvironmentStatusDto.serializer(), status))
            .build()
    }

    override suspend fun cloneEnvironment(request: CloneEnvironmentRequest): EnvironmentResponse {
        val newNamespace = request.newNamespace.takeIf { it.isNotBlank() }
            ?: request.newName.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        val newTier = request.newTier.takeIf { it.isNotBlank() }
            ?.let { EnvironmentTier.valueOf(it.uppercase()) }
        val cloned = environmentService.cloneEnvironment(
            sourceId = EnvironmentId(ObjectId(request.environmentId)),
            newName = request.newName,
            newNamespace = newNamespace,
            targetClientId = request.targetClientId.takeIf { it.isNotBlank() }?.let { ClientId(ObjectId(it)) },
            targetGroupId = request.targetGroupId.takeIf { it.isNotBlank() }?.let { ProjectGroupId(ObjectId(it)) },
            targetProjectId = request.targetProjectId.takeIf { it.isNotBlank() }?.let { ProjectId(ObjectId(it)) },
            newTier = newTier,
        )
        return cloned.toEnvironmentResponse()
    }

    override suspend fun addPropertyMapping(request: AddPropertyMappingRequest): EnvironmentResponse {
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
        val saved = environmentService.saveEnvironment(env.copy(propertyMappings = env.propertyMappings + mapping))
        return saved.toEnvironmentResponse()
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
            .setBodyJson(json.encodeToString(EnvironmentDto.serializer(), saved.toDto()))
            .setMappingsAdded(addedCount)
            .build()
    }

    override suspend fun discoverNamespace(request: DiscoverNamespaceRequest): EnvironmentResponse {
        val tier = request.tier.takeIf { it.isNotBlank() }
            ?.let { EnvironmentTier.valueOf(it.uppercase()) } ?: EnvironmentTier.DEV
        val env = environmentK8sService.discoverFromNamespace(
            namespace = request.namespace,
            clientId = ClientId(ObjectId(request.clientId)),
            name = request.name.takeIf { it.isNotBlank() },
            tier = tier,
        )
        return env.toEnvironmentResponse()
    }

    override suspend fun replicateEnvironment(request: ReplicateEnvironmentRequest): EnvironmentResponse {
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
        val deployed = environmentK8sService.provisionEnvironment(cloned.id)
        return deployed.toEnvironmentResponse()
    }

    override suspend fun syncFromK8s(request: EnvironmentIdRequest): EnvironmentResponse {
        val env = environmentK8sService.syncFromK8s(EnvironmentId(ObjectId(request.environmentId)))
        return env.toEnvironmentResponse()
    }

    override suspend fun listComponentTemplates(
        request: ListComponentTemplatesRequest,
    ): ListComponentTemplatesResponse {
        val templates = COMPONENT_DEFAULTS.map { (type, defaults) ->
            mapOf(
                "type" to type.name,
                "versions" to defaults.versions.map { mapOf("label" to it.label, "image" to it.image) },
                "defaultPorts" to defaults.ports.map {
                    mapOf(
                        "containerPort" to it.containerPort,
                        "servicePort" to it.servicePort,
                        "name" to it.name,
                    )
                },
                "defaultEnvVars" to defaults.defaultEnvVars,
                "defaultVolumeMountPath" to defaults.defaultVolumeMountPath,
            )
        }
        val arr = buildJsonArray {
            templates.forEach { template ->
                add(buildJsonObject {
                    template.forEach { (k, v) -> put(k, toJsonElement(v)) }
                })
            }
        }
        return ListComponentTemplatesResponse.newBuilder().setItemsJson(arr.toString()).build()
    }

    // ── Helpers ──

    private fun EnvironmentDocument.toEnvironmentResponse(): EnvironmentResponse =
        EnvironmentResponse.newBuilder()
            .setBodyJson(json.encodeToString(EnvironmentDto.serializer(), toDto()))
            .build()

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            value.forEach { (k, v) -> put(k.toString(), toJsonElement(v)) }
        }
        is List<*> -> buildJsonArray {
            value.forEach { add(toJsonElement(it)) }
        }
        else -> JsonPrimitive(value.toString())
    }
}
