package com.jervis.service

import com.jervis.dto.ClientDto
import com.jervis.dto.git.GitAnalysisResultDto
import kotlinx.rpc.annotations.Rpc

/**
 * Client Service API
 */
@Rpc
interface IClientService {

    suspend fun getAllClients(): List<ClientDto>

    suspend fun getClientById(id: String): ClientDto?

    suspend fun createClient(client: ClientDto): ClientDto

    suspend fun updateClient(
        id: String,
        client: ClientDto
    ): ClientDto

    suspend fun deleteClient(id: String)

    suspend fun updateLastSelectedProject(
        id: String,
        projectId: String?
    ): ClientDto

    /**
     * Analyze all git repositories for a client to extract commit patterns.
     * Clones repositories if needed, then analyzes:
     * - Top committers
     * - Commit message patterns
     * - GPG signing usage
     */
    suspend fun analyzeGitRepositories(clientId: String): GitAnalysisResultDto
}
