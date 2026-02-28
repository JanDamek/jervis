package com.jervis.mapper

import com.jervis.common.types.ClientId
import com.jervis.common.types.EnvironmentId
import com.jervis.common.types.ProjectGroupId
import com.jervis.common.types.ProjectId
import com.jervis.dto.environment.*
import com.jervis.entity.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bson.types.ObjectId

fun EnvironmentDocument.toDto(): EnvironmentDto =
    EnvironmentDto(
        id = this.id.toString(),
        clientId = this.clientId.toString(),
        groupId = this.groupId?.toString(),
        projectId = this.projectId?.toString(),
        name = this.name,
        description = this.description,
        tier = EnvironmentTierEnum.valueOf(this.tier.name),
        namespace = this.namespace,
        components = this.components.map { it.toDto() },
        componentLinks = this.componentLinks.map { it.toDto() },
        propertyMappings = this.propertyMappings.map { it.toDto() },
        agentInstructions = this.agentInstructions,
        state = EnvironmentStateEnum.valueOf(this.state.name),
        storageSizeGi = this.storageSizeGi,
        yamlManifests = this.yamlManifests,
    )

fun EnvironmentDto.toDocument(): EnvironmentDocument {
    val resolvedId = if (ObjectId.isValid(this.id)) ObjectId(this.id) else ObjectId.get()

    return EnvironmentDocument(
        id = EnvironmentId(resolvedId),
        clientId = ClientId(ObjectId(this.clientId)),
        groupId = this.groupId?.let { ProjectGroupId(ObjectId(it)) },
        projectId = this.projectId?.let { ProjectId(ObjectId(it)) },
        name = this.name,
        description = this.description,
        tier = EnvironmentTier.valueOf(this.tier.name),
        namespace = this.namespace,
        components = this.components.map { it.toEntity() },
        componentLinks = this.componentLinks.map { it.toEntity() },
        propertyMappings = this.propertyMappings.map { it.toEntity() },
        agentInstructions = this.agentInstructions,
        state = EnvironmentState.valueOf(this.state.name),
        storageSizeGi = this.storageSizeGi,
        yamlManifests = this.yamlManifests,
    )
}

/**
 * Convert environment to agent-consumable context (for passing to orchestrator).
 */
fun EnvironmentDocument.toAgentContext(): Map<String, Any?> {
    val infraComponents = components.filter { it.type != ComponentType.PROJECT && it.autoStart }
    val projectComponents = components.filter { it.type == ComponentType.PROJECT }

    return mapOf(
        "namespace" to namespace,
        "tier" to tier.name,
        "state" to state.name,
        "components" to components.map { comp ->
            val resolved = propertyMappings
                .filter { it.projectComponentId == comp.id }
                .associate { it.propertyName to (it.resolvedValue ?: it.valueTemplate) }

            mapOf(
                "id" to comp.id,
                "name" to comp.name,
                "type" to comp.type.name,
                "image" to comp.image,
                "projectId" to comp.projectId,
                "host" to "${comp.name}.${namespace}.svc.cluster.local",
                "ports" to comp.ports.map { mapOf("container" to it.containerPort, "service" to (it.servicePort ?: it.containerPort), "name" to it.name) },
                "envVars" to (comp.envVars + resolved),
                "autoStart" to comp.autoStart,
                "startOrder" to comp.startOrder,
                "sourceRepo" to comp.sourceRepo,
                "sourceBranch" to comp.sourceBranch,
                "dockerfilePath" to comp.dockerfilePath,
                "componentState" to comp.componentState.name,
            )
        },
        "componentLinks" to componentLinks.map { link ->
            val sourceName = components.find { it.id == link.sourceComponentId }?.name ?: link.sourceComponentId
            val targetName = components.find { it.id == link.targetComponentId }?.name ?: link.targetComponentId
            mapOf("source" to sourceName, "target" to targetName, "description" to link.description)
        },
        "agentInstructions" to agentInstructions,
    )
}

/**
 * Convert environment to JsonObject for passing to Python orchestrator via HTTP.
 */
fun EnvironmentDocument.toAgentContextJson(): JsonObject = buildJsonObject {
    put("namespace", namespace)
    put("tier", tier.name)
    put("state", state.name)
    put("groupId", groupId?.toString())
    put("agentInstructions", agentInstructions)
    put("components", buildJsonArray {
        for (comp in components) {
            add(buildJsonObject {
                put("id", comp.id)
                put("name", comp.name)
                put("type", comp.type.name)
                put("image", comp.image)
                put("projectId", comp.projectId)
                put("host", "${comp.name}.${namespace}.svc.cluster.local")
                put("ports", buildJsonArray {
                    for (p in comp.ports) {
                        add(buildJsonObject {
                            put("container", p.containerPort)
                            put("service", p.servicePort ?: p.containerPort)
                            put("name", p.name)
                        })
                    }
                })
                put("envVars", buildJsonObject {
                    val resolved = propertyMappings
                        .filter { it.projectComponentId == comp.id }
                        .associate { it.propertyName to (it.resolvedValue ?: it.valueTemplate) }
                    for ((k, v) in comp.envVars + resolved) {
                        put(k, v)
                    }
                })
                put("autoStart", comp.autoStart)
                put("startOrder", comp.startOrder)
                put("sourceRepo", comp.sourceRepo)
                put("sourceBranch", comp.sourceBranch)
                put("dockerfilePath", comp.dockerfilePath)
                put("componentState", comp.componentState.name)
            })
        }
    })
    put("componentLinks", buildJsonArray {
        for (link in componentLinks) {
            val sourceName = components.find { it.id == link.sourceComponentId }?.name ?: link.sourceComponentId
            val targetName = components.find { it.id == link.targetComponentId }?.name ?: link.targetComponentId
            add(buildJsonObject {
                put("source", sourceName)
                put("target", targetName)
                put("description", link.description)
            })
        }
    })
}

// --- Component mapping ---

fun EnvironmentComponent.toDto(): EnvironmentComponentDto =
    EnvironmentComponentDto(
        id = this.id,
        name = this.name,
        type = ComponentTypeEnum.valueOf(this.type.name),
        image = this.image,
        projectId = this.projectId,
        cpuLimit = this.cpuLimit,
        memoryLimit = this.memoryLimit,
        ports = this.ports.map { it.toDto() },
        envVars = this.envVars,
        autoStart = this.autoStart,
        startOrder = this.startOrder,
        healthCheckPath = this.healthCheckPath,
        volumeMountPath = this.volumeMountPath,
        sourceRepo = this.sourceRepo,
        sourceBranch = this.sourceBranch,
        dockerfilePath = this.dockerfilePath,
        deploymentYaml = this.deploymentYaml,
        serviceYaml = this.serviceYaml,
        configMapData = this.configMapData,
        componentState = ComponentStateEnum.valueOf(this.componentState.name),
    )

fun EnvironmentComponentDto.toEntity(): EnvironmentComponent =
    EnvironmentComponent(
        id = this.id.ifEmpty { ObjectId.get().toString() },
        name = this.name,
        type = ComponentType.valueOf(this.type.name),
        image = this.image,
        projectId = this.projectId,
        cpuLimit = this.cpuLimit,
        memoryLimit = this.memoryLimit,
        ports = this.ports.map { it.toEntity() },
        envVars = this.envVars,
        autoStart = this.autoStart,
        startOrder = this.startOrder,
        healthCheckPath = this.healthCheckPath,
        volumeMountPath = this.volumeMountPath,
        sourceRepo = this.sourceRepo,
        sourceBranch = this.sourceBranch,
        dockerfilePath = this.dockerfilePath,
        deploymentYaml = this.deploymentYaml,
        serviceYaml = this.serviceYaml,
        configMapData = this.configMapData,
        componentState = ComponentState.valueOf(this.componentState.name),
    )

fun PortMapping.toDto(): PortMappingDto =
    PortMappingDto(containerPort = this.containerPort, servicePort = this.servicePort, name = this.name)

fun PortMappingDto.toEntity(): PortMapping =
    PortMapping(containerPort = this.containerPort, servicePort = this.servicePort, name = this.name)

fun ComponentLink.toDto(): ComponentLinkDto =
    ComponentLinkDto(sourceComponentId = this.sourceComponentId, targetComponentId = this.targetComponentId, description = this.description)

fun ComponentLinkDto.toEntity(): ComponentLink =
    ComponentLink(sourceComponentId = this.sourceComponentId, targetComponentId = this.targetComponentId, description = this.description)

fun PropertyMapping.toDto(): PropertyMappingDto =
    PropertyMappingDto(
        projectComponentId = this.projectComponentId,
        propertyName = this.propertyName,
        targetComponentId = this.targetComponentId,
        valueTemplate = this.valueTemplate,
        resolvedValue = this.resolvedValue,
    )

fun PropertyMappingDto.toEntity(): PropertyMapping =
    PropertyMapping(
        projectComponentId = this.projectComponentId,
        propertyName = this.propertyName,
        targetComponentId = this.targetComponentId,
        valueTemplate = this.valueTemplate,
        resolvedValue = this.resolvedValue,
    )
