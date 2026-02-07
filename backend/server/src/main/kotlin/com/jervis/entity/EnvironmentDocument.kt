package com.jervis.entity

import com.jervis.common.types.ClientId
import com.jervis.common.types.EnvironmentId
import com.jervis.common.types.ProjectGroupId
import com.jervis.common.types.ProjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/**
 * MongoDB document representing a runtime environment definition.
 *
 * An environment describes a K8s namespace with infrastructure components
 * (databases, caches, etc.) and project components, their interconnections,
 * and property mappings.
 *
 * Scoping with inheritance: Client -> Group -> Project
 * - Environment at client level applies to all groups/projects
 * - Environment at group level overrides/extends for that group's projects
 * - Environment at project level overrides/extends for that specific project
 *
 * The server creates the K8s namespace and deploys infrastructure components.
 * The agent is responsible for starting project components.
 */
@Document(collection = "environments")
data class EnvironmentDocument(
    @Id
    val id: EnvironmentId = EnvironmentId.generate(),
    @Indexed
    val clientId: ClientId,
    /** Optional: scoped to a specific group (null = client-level or project-level) */
    val groupId: ProjectGroupId? = null,
    /** Optional: scoped to a specific project (null = client-level or group-level) */
    val projectId: ProjectId? = null,
    val name: String,
    val description: String? = null,
    /** K8s namespace for this environment */
    val namespace: String,
    /** Infrastructure and project components */
    val components: List<EnvironmentComponent> = emptyList(),
    /** How components interconnect (who talks to whom) */
    val componentLinks: List<ComponentLink> = emptyList(),
    /** Property mappings: which ENV vars in projects map to which component */
    val propertyMappings: List<PropertyMapping> = emptyList(),
    /** Free-text instructions for the agent about this environment */
    val agentInstructions: String? = null,
    /** State of the K8s namespace */
    val state: EnvironmentState = EnvironmentState.PENDING,
)

/**
 * A component in the environment (infrastructure or project).
 */
data class EnvironmentComponent(
    /** Unique ID within this environment */
    val id: String,
    /** Human-readable name (also used as K8s service/deployment name) */
    val name: String,
    /** Component type */
    val type: ComponentType,
    /** Docker image (for infrastructure components) */
    val image: String? = null,
    /** Reference to ProjectDocument.id (for PROJECT type components) */
    val projectId: String? = null,
    /** K8s CPU limit, e.g., "500m" */
    val cpuLimit: String? = null,
    /** K8s memory limit, e.g., "512Mi" */
    val memoryLimit: String? = null,
    /** Port mappings */
    val ports: List<PortMapping> = emptyList(),
    /** Raw environment variables for this component */
    val envVars: Map<String, String> = emptyMap(),
    /** Whether server should auto-deploy this component (only for infrastructure) */
    val autoStart: Boolean = true,
    /** Startup order (lower = starts first) */
    val startOrder: Int = 0,
    /** Health check endpoint (e.g., "/health") */
    val healthCheckPath: String? = null,
)

enum class ComponentType {
    // Infrastructure components
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
    // Project reference
    PROJECT,
}

data class PortMapping(
    val containerPort: Int,
    val servicePort: Int? = null,
    val name: String = "",
)

/**
 * Link between components describing communication dependency.
 */
data class ComponentLink(
    val sourceComponentId: String,
    val targetComponentId: String,
    /** Description of the dependency, e.g., "uses for user data" */
    val description: String = "",
)

/**
 * Maps a project's config property/ENV var to a specific infrastructure component endpoint.
 */
data class PropertyMapping(
    /** ID of the project component whose config this maps */
    val projectComponentId: String,
    /** The ENV var or config property name in the project, e.g., "SPRING_DATASOURCE_URL" */
    val propertyName: String,
    /** ID of the infrastructure component this property points to */
    val targetComponentId: String,
    /** Template for the value, with placeholders, e.g., "jdbc:postgresql://{host}:{port}/{db}" */
    val valueTemplate: String,
    /** Resolved value (filled by server when namespace is provisioned) */
    val resolvedValue: String? = null,
)

enum class EnvironmentState {
    PENDING,
    CREATING,
    RUNNING,
    STOPPING,
    STOPPED,
    ERROR,
}
