package com.jervis.dto.environment

import com.jervis.common.Constants
import kotlinx.serialization.Serializable

@Serializable
data class EnvironmentDto(
    val id: String = Constants.GLOBAL_ID_STRING,
    val clientId: String,
    val groupId: String? = null,
    val projectId: String? = null,
    val name: String,
    val description: String? = null,
    val namespace: String,
    val components: List<EnvironmentComponentDto> = emptyList(),
    val componentLinks: List<ComponentLinkDto> = emptyList(),
    val propertyMappings: List<PropertyMappingDto> = emptyList(),
    val agentInstructions: String? = null,
    val state: EnvironmentStateEnum = EnvironmentStateEnum.PENDING,
)

@Serializable
data class EnvironmentComponentDto(
    val id: String = "",
    val name: String,
    val type: ComponentTypeEnum,
    val image: String? = null,
    val projectId: String? = null,
    val cpuLimit: String? = null,
    val memoryLimit: String? = null,
    val ports: List<PortMappingDto> = emptyList(),
    val envVars: Map<String, String> = emptyMap(),
    val autoStart: Boolean = true,
    val startOrder: Int = 0,
    val healthCheckPath: String? = null,
)

@Serializable
enum class ComponentTypeEnum {
    POSTGRESQL,
    MONGODB,
    REDIS,
    RABBITMQ,
    KAFKA,
    ELASTICSEARCH,
    ORACLE,
    MYSQL,
    MINIO,
    CUSTOM_INFRA,
    PROJECT,
}

@Serializable
enum class EnvironmentStateEnum {
    PENDING,
    CREATING,
    RUNNING,
    STOPPING,
    STOPPED,
    ERROR,
}

@Serializable
data class PortMappingDto(
    val containerPort: Int,
    val servicePort: Int? = null,
    val name: String = "",
)

@Serializable
data class ComponentLinkDto(
    val sourceComponentId: String,
    val targetComponentId: String,
    val description: String = "",
)

@Serializable
data class PropertyMappingDto(
    val projectComponentId: String,
    val propertyName: String,
    val targetComponentId: String,
    val valueTemplate: String,
    val resolvedValue: String? = null,
)

/**
 * Status of all components in an environment.
 */
@Serializable
data class EnvironmentStatusDto(
    val environmentId: String,
    val namespace: String,
    val state: EnvironmentStateEnum,
    val componentStatuses: List<ComponentStatusDto> = emptyList(),
)

@Serializable
data class ComponentStatusDto(
    val componentId: String,
    val name: String,
    val ready: Boolean,
    val replicas: Int = 0,
    val availableReplicas: Int = 0,
    val message: String? = null,
)
