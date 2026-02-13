package com.jervis.service

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
}
