package com.jervis.service

import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResourceDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionTestResultDto
import com.jervis.dto.connection.ConnectionUpdateRequestDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IConnectionService {
    suspend fun getAllConnections(): List<ConnectionResponseDto>

    suspend fun getConnectionById(id: String): ConnectionResponseDto?

    suspend fun createConnection(request: ConnectionCreateRequestDto): ConnectionResponseDto

    suspend fun updateConnection(
        id: String,
        request: ConnectionUpdateRequestDto,
    ): ConnectionResponseDto

    suspend fun deleteConnection(id: String)

    suspend fun testConnection(id: String): ConnectionTestResultDto

    suspend fun listImportableProjects(connectionId: String): List<com.jervis.dto.connection.ConnectionImportProjectDto>

    suspend fun importProject(
        connectionId: String,
        externalId: String,
    ): com.jervis.dto.ProjectDto

    suspend fun initiateOAuth2(connectionId: String): String

    /**
     * List available resources for a given capability from a connection.
     * Used to populate UI dropdowns for selecting which resources to index.
     *
     * @param connectionId The connection to query
     * @param capability The capability type (BUGTRACKER, WIKI, EMAIL, REPOSITORY, GIT)
     * @return List of available resources (projects, spaces, folders, repos)
     */
    suspend fun listAvailableResources(
        connectionId: String,
        capability: ConnectionCapability,
    ): List<ConnectionResourceDto>
}
