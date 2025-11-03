package com.jervis.controller.api

import com.jervis.domain.jira.JiraAccountId
import com.jervis.domain.jira.JiraBoardId
import com.jervis.domain.jira.JiraProjectKey
import com.jervis.dto.events.JiraAuthPromptEventDto
import com.jervis.dto.jira.JiraApiTokenSaveRequestDto
import com.jervis.dto.jira.JiraApiTokenTestRequestDto
import com.jervis.dto.jira.JiraApiTokenTestResponseDto
import com.jervis.dto.jira.JiraBeginAuthRequestDto
import com.jervis.dto.jira.JiraBeginAuthResponseDto
import com.jervis.dto.jira.JiraBoardSelectionDto
import com.jervis.dto.jira.JiraCompleteAuthRequestDto
import com.jervis.dto.jira.JiraProjectSelectionDto
import com.jervis.dto.jira.JiraSetupStatusDto
import com.jervis.dto.jira.JiraUserSelectionDto
import com.jervis.service.IJiraSetupService
import com.jervis.service.jira.JiraAuthService
import com.jervis.service.jira.JiraSelectionService
import com.jervis.service.websocket.WebSocketChannelType
import com.jervis.service.websocket.WebSocketSessionManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
class JiraSetupRestController(
    private val jiraAuthService: JiraAuthService,
    private val jiraSelectionService: JiraSelectionService,
    private val sessionManager: WebSocketSessionManager,
    private val jiraApiClient: com.jervis.service.jira.JiraApiClient,
) : IJiraSetupService {
    private val logger = KotlinLogging.logger {}
    private val json = Json { encodeDefaults = true }

    override suspend fun getStatus(clientId: String): JiraSetupStatusDto {
        val status = fetchStatus(clientId)
        logger.info { "JIRA_UI_SETUP: status requested for client=$clientId connected=${status.connected}" }
        return status
    }

    override suspend fun testApiToken(request: JiraApiTokenTestRequestDto): JiraApiTokenTestResponseDto {
        val ok = jiraAuthService.testApiToken(request.tenant, request.email, request.apiToken)
        logger.info { "JIRA_UI_SETUP: testApiToken tenant=${request.tenant} email=${request.email} ok=$ok" }
        return JiraApiTokenTestResponseDto(success = ok, message = if (ok) null else "Unauthorized")
    }

    override suspend fun saveApiToken(request: JiraApiTokenSaveRequestDto): JiraSetupStatusDto {
        val conn = jiraAuthService.saveApiToken(request.clientId, request.tenant, request.email, request.apiToken)
        logger.info { "JIRA_UI_SETUP: saveApiToken succeeded for client=${conn.clientId} tenant=${conn.tenant.value}" }
        return fetchStatus(request.clientId)
    }

    override suspend fun beginAuth(request: JiraBeginAuthRequestDto): JiraBeginAuthResponseDto {
        val correlationId = UUID.randomUUID().toString()
        val authUrl = jiraAuthService.beginCloudOauth(request.clientId, request.tenant, request.redirectUri)

        val event =
            JiraAuthPromptEventDto(
                clientId = request.clientId,
                correlationId = correlationId,
                authUrl = authUrl,
                redirectUri = request.redirectUri,
            )
        val payload = json.encodeToString(event)
        sessionManager.broadcastToChannel(payload, WebSocketChannelType.NOTIFICATIONS)
        logger.info { "JIRA_UI_SETUP: beginAuth published prompt for client=${request.clientId} correlationId=$correlationId" }

        return JiraBeginAuthResponseDto(correlationId = correlationId, authUrl = authUrl)
    }

    override suspend fun completeAuth(request: JiraCompleteAuthRequestDto): JiraSetupStatusDto {
        val conn =
            jiraAuthService.completeCloudOauth(
                request.clientId,
                request.tenant,
                request.code,
                request.verifier,
                request.redirectUri,
            )
        logger.info { "JIRA_UI_SETUP: completeAuth succeeded for client=${conn.clientId} tenant=${conn.tenant.value}" }
        return fetchStatus(request.clientId)
    }

    override suspend fun setPrimaryProject(request: JiraProjectSelectionDto): JiraSetupStatusDto {
        jiraSelectionService.setPrimaryProject(ObjectId(request.clientId), JiraProjectKey(request.projectKey))
        logger.info { "JIRA_UI_SETUP: setPrimaryProject client=${request.clientId} project=${request.projectKey}" }
        return fetchStatus(request.clientId)
    }

    override suspend fun setMainBoard(request: JiraBoardSelectionDto): JiraSetupStatusDto {
        jiraSelectionService.setMainBoard(ObjectId(request.clientId), JiraBoardId(request.boardId))
        logger.info { "JIRA_UI_SETUP: setMainBoard client=${request.clientId} board=${request.boardId}" }
        return fetchStatus(request.clientId)
    }

    override suspend fun setPreferredUser(request: JiraUserSelectionDto): JiraSetupStatusDto {
        jiraSelectionService.setPreferredUser(ObjectId(request.clientId), JiraAccountId(request.accountId))
        logger.info { "JIRA_UI_SETUP: setPreferredUser client=${request.clientId} account=${request.accountId}" }
        return fetchStatus(request.clientId)
    }

    override suspend fun listProjects(clientId: String): List<com.jervis.dto.jira.JiraProjectRefDto> {
        val conn = jiraSelectionService.getConnection(ObjectId(clientId))
        val list = jiraApiClient.listProjects(conn)
        return list.map {
            com.jervis.dto.jira
                .JiraProjectRefDto(key = it.first.value, name = it.second)
        }
    }

    override suspend fun listBoards(
        clientId: String,
        projectKey: String?,
    ): List<com.jervis.dto.jira.JiraBoardRefDto> {
        val conn = jiraSelectionService.getConnection(ObjectId(clientId))
        val boards =
            jiraApiClient.listBoards(
                conn,
                projectKey?.let {
                    JiraProjectKey(it)
                },
            )
        return boards.map {
            com.jervis.dto.jira
                .JiraBoardRefDto(id = it.first.value, name = it.second)
        }
    }

    private suspend fun fetchStatus(clientId: String): JiraSetupStatusDto =
        try {
            val conn = jiraSelectionService.getConnection(ObjectId(clientId))
            JiraSetupStatusDto(
                clientId = clientId,
                connected = conn.expiresAt.isAfter(Instant.now()),
                tenant = conn.tenant.value,
                primaryProject = conn.primaryProject?.value,
                mainBoard = conn.mainBoard?.value,
                preferredUser = conn.preferredUser?.value,
            )
        } catch (e: Exception) {
            // Not configured yet or invalid; return disconnected status
            JiraSetupStatusDto(
                clientId = clientId,
                connected = false,
            )
        }
}
