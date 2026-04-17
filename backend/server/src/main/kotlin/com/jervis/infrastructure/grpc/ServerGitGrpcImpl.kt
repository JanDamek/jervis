package com.jervis.infrastructure.grpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.contracts.server.CreateRepositoryRequest
import com.jervis.contracts.server.CreateRepositoryResponse
import com.jervis.contracts.server.GetGpgKeyRequest
import com.jervis.contracts.server.GetGpgKeyResponse
import com.jervis.contracts.server.InitWorkspaceRequest
import com.jervis.contracts.server.InitWorkspaceResponse
import com.jervis.contracts.server.ServerGitServiceGrpcKt
import com.jervis.contracts.server.WorkspaceStatusRequest
import com.jervis.contracts.server.WorkspaceStatusResponse
import com.jervis.git.rpc.GpgCertificateRpcImpl
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.git.GitRepoCreationResult
import com.jervis.git.GitRepositoryCreationService
import com.jervis.git.service.GitRepositoryService
import com.jervis.project.ProjectService
import com.jervis.project.WorkspaceStatus
import com.jervis.task.BackgroundEngine
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
class ServerGitGrpcImpl(
    private val gitRepoCreationService: GitRepositoryCreationService,
    private val projectService: ProjectService,
    private val backgroundEngine: BackgroundEngine,
    private val gitRepositoryService: GitRepositoryService,
    private val gpgCertificateRpcImpl: GpgCertificateRpcImpl,
) : ServerGitServiceGrpcKt.ServerGitServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun createRepository(request: CreateRepositoryRequest): CreateRepositoryResponse {
        val result = try {
            gitRepoCreationService.createRepository(
                clientId = ClientId(ObjectId(request.clientId)),
                connectionId = request.connectionId.takeIf { it.isNotBlank() },
                name = request.name,
                description = request.description.takeIf { it.isNotBlank() },
                isPrivate = request.isPrivate,
            )
        } catch (e: IllegalArgumentException) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription(e.message))
        }
        return CreateRepositoryResponse.newBuilder()
            .setBodyJson(json.encodeToString(GitRepoCreationResult.serializer(), result))
            .build()
    }

    override suspend fun initWorkspace(request: InitWorkspaceRequest): InitWorkspaceResponse {
        val projectId = try {
            ProjectId(ObjectId(request.projectId))
        } catch (_: Exception) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("invalid project_id"))
        }
        val project = projectService.getProjectByIdOrNull(projectId)
            ?: throw StatusException(
                Status.NOT_FOUND.withDescription("Project ${request.projectId} not found"),
            )

        if (project.workspaceStatus == WorkspaceStatus.READY) {
            val gitRes = project.resources.firstOrNull { it.capability == ConnectionCapability.REPOSITORY }
            val path = gitRes?.let { gitRepositoryService.getAgentRepoDir(project, it).toString() } ?: ""
            return InitWorkspaceResponse.newBuilder()
                .setOk(true)
                .setProjectId(request.projectId)
                .setStatus("READY")
                .setWorkspacePath(path)
                .build()
        }

        backgroundEngine.initializeProjectWorkspace(project)
        val refreshed = projectService.getProjectByIdOrNull(projectId)
        val status = refreshed?.workspaceStatus?.name ?: "UNKNOWN"
        val gitRes = refreshed?.resources?.firstOrNull { it.capability == ConnectionCapability.REPOSITORY }
        val path = if (refreshed?.workspaceStatus == WorkspaceStatus.READY && gitRes != null) {
            gitRepositoryService.getAgentRepoDir(refreshed, gitRes).toString()
        } else ""
        val err = refreshed?.lastWorkspaceError ?: ""

        return InitWorkspaceResponse.newBuilder()
            .setOk(refreshed?.workspaceStatus == WorkspaceStatus.READY)
            .setProjectId(request.projectId)
            .setStatus(status)
            .setWorkspacePath(path)
            .setError(err)
            .build()
    }

    override suspend fun getGpgKey(request: GetGpgKeyRequest): GetGpgKeyResponse {
        return try {
            val keyInfo = gpgCertificateRpcImpl.getActiveKey(
                request.clientId,
                request.gpgKeyId.takeIf { it.isNotBlank() },
            )
            if (keyInfo == null) {
                GetGpgKeyResponse.newBuilder().setHasKey(false).build()
            } else {
                GetGpgKeyResponse.newBuilder()
                    .setHasKey(true)
                    .setKeyId(keyInfo.keyId)
                    .setUserName(keyInfo.userName)
                    .setUserEmail(keyInfo.userEmail)
                    .setPrivateKeyArmored(keyInfo.privateKeyArmored)
                    .setPassphrase(keyInfo.passphrase.orEmpty())
                    .build()
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch GPG key for client ${request.clientId}" }
            GetGpgKeyResponse.newBuilder().setHasKey(false).setError(e.message.orEmpty()).build()
        }
    }

    override suspend fun getWorkspaceStatus(request: WorkspaceStatusRequest): WorkspaceStatusResponse {
        val projectId = try {
            ProjectId(ObjectId(request.projectId))
        } catch (_: Exception) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("invalid project_id"))
        }
        val project = projectService.getProjectByIdOrNull(projectId)
            ?: throw StatusException(
                Status.NOT_FOUND.withDescription("Project ${request.projectId} not found"),
            )
        val status = project.workspaceStatus?.name ?: "NONE"
        val gitRes = project.resources.firstOrNull { it.capability == ConnectionCapability.REPOSITORY }
        val path = if (project.workspaceStatus == WorkspaceStatus.READY && gitRes != null) {
            gitRepositoryService.getAgentRepoDir(project, gitRes).toString()
        } else ""
        return WorkspaceStatusResponse.newBuilder()
            .setProjectId(request.projectId)
            .setStatus(status)
            .setWorkspacePath(path)
            .setError(project.lastWorkspaceError ?: "")
            .build()
    }
}
