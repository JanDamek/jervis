package com.jervis.service.environment

import com.jervis.dto.environment.ComponentTemplateDto
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.EnvironmentStatusDto
import kotlinx.rpc.annotations.Rpc

/**
 * Environment Service API.
 * kotlinx-rpc will auto-generate client implementation for Desktop/Mobile.
 */
@Rpc
interface IEnvironmentService {
    suspend fun getAllEnvironments(): List<EnvironmentDto>

    suspend fun listEnvironments(clientId: String): List<EnvironmentDto>

    suspend fun getEnvironment(id: String): EnvironmentDto

    suspend fun saveEnvironment(environment: EnvironmentDto): EnvironmentDto

    suspend fun updateEnvironment(id: String, environment: EnvironmentDto): EnvironmentDto

    suspend fun deleteEnvironment(id: String)

    /** Create K8s namespace and deploy infrastructure components */
    suspend fun provisionEnvironment(id: String): EnvironmentDto

    /** Tear down infrastructure components and optionally delete namespace */
    suspend fun deprovisionEnvironment(id: String): EnvironmentDto

    /** Get status of all components in the environment */
    suspend fun getEnvironmentStatus(id: String): EnvironmentStatusDto

    /**
     * Resolve the effective environment for a project.
     * Follows inheritance: project -> group -> client.
     * Returns null if no environment is defined.
     */
    suspend fun resolveEnvironmentForProject(projectId: String): EnvironmentDto?

    /** Get available component templates with version lists from COMPONENT_DEFAULTS. */
    suspend fun getComponentTemplates(): List<ComponentTemplateDto>

    /** Sync K8s resources (ConfigMaps, re-apply deployments) for a RUNNING environment. */
    suspend fun syncEnvironmentResources(id: String): EnvironmentDto

    /** Get logs for a component (pod) in an environment. */
    suspend fun getComponentLogs(environmentId: String, componentName: String, tailLines: Int = 200): String

    /** Clone an environment to a new scope (different client, group, or project). */
    suspend fun cloneEnvironment(
        sourceId: String,
        newName: String,
        newNamespace: String,
        targetClientId: String? = null,
        targetGroupId: String? = null,
        targetProjectId: String? = null,
        newTier: String? = null,
    ): EnvironmentDto
}
