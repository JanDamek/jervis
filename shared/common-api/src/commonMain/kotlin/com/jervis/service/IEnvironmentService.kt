package com.jervis.service

import com.jervis.dto.environment.ComponentTemplateDto
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.EnvironmentStatusDto
import com.jervis.dto.environment.ExecResultDto
import com.jervis.dto.environment.FileUploadResultDto
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

    /**
     * Upload a file to a running component pod.
     * @param id Environment ID
     * @param componentName Component name or ID
     * @param fileName Original file name
     * @param fileBase64 File content encoded as Base64
     * @param targetDir Target directory in pod (default /tmp)
     */
    suspend fun uploadFileToComponent(
        id: String,
        componentName: String,
        fileName: String,
        fileBase64: String,
        targetDir: String = "/tmp",
    ): FileUploadResultDto

    /**
     * Execute a command inside a running component pod.
     * @param id Environment ID
     * @param componentName Component name or ID
     * @param command Command as list of strings (e.g., ["psql", "-f", "/tmp/dump.sql"])
     */
    suspend fun execInComponent(
        id: String,
        componentName: String,
        command: List<String>,
    ): ExecResultDto
}
