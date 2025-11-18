package com.jervis.controller.api

import com.jervis.domain.jira.JiraAccountId
import com.jervis.domain.jira.JiraBoardId
import com.jervis.domain.jira.JiraProjectKey
import com.jervis.domain.websocket.WebSocketChannelTypeEnum
import com.jervis.dto.events.AtlassianAuthPromptEventDto
import com.jervis.dto.atlassian.AtlassianApiTokenSaveRequestDto
import com.jervis.dto.atlassian.AtlassianApiTokenTestRequestDto
import com.jervis.dto.atlassian.AtlassianApiTokenTestResponseDto
import com.jervis.dto.atlassian.AtlassianBeginAuthRequestDto
import com.jervis.dto.atlassian.AtlassianBeginAuthResponseDto
import com.jervis.dto.atlassian.AtlassianBoardRefDto
import com.jervis.dto.atlassian.AtlassianBoardSelectionDto
import com.jervis.dto.atlassian.AtlassianCompleteAuthRequestDto
import com.jervis.dto.atlassian.AtlassianProjectRefDto
import com.jervis.dto.atlassian.AtlassianProjectSelectionDto
import com.jervis.dto.atlassian.AtlassianSetupStatusDto
import com.jervis.dto.atlassian.AtlassianUserSelectionDto
import com.jervis.service.IAtlassianSetupService
import com.jervis.service.atlassian.AtlassianAuthService
import com.jervis.service.atlassian.AtlassianSelectionService
import com.jervis.service.websocket.WebSocketSessionManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/atlassian/setup")
class AtlassianSetupRestController(
    private val jiraAuthService: AtlassianAuthService,
    private val jiraSelectionService: AtlassianSelectionService,
    private val sessionManager: WebSocketSessionManager,
    private val jiraApiClient: com.jervis.service.atlassian.AtlassianApiClient,
    private val errorPublisher: com.jervis.service.notification.ErrorNotificationsPublisher,
    private val jiraConnectionService: com.jervis.service.atlassian.AtlassianConnectionService,
) : IAtlassianSetupService {
    private val logger = KotlinLogging.logger {}
    private val json = Json { encodeDefaults = true }

    @GetMapping("/status")
    override suspend fun getStatus(@RequestParam clientId: String): AtlassianSetupStatusDto {
        val status = fetchStatus(clientId)
        logger.info { "JIRA_UI_SETUP: status requested for client=$clientId connected=${status.connected}" }
        return status
    }

    @PostMapping("/test-api-token")
    override suspend fun testApiToken(@RequestBody request: AtlassianApiTokenTestRequestDto): AtlassianApiTokenTestResponseDto =
        try {
            val ok = jiraAuthService.testApiToken(request.tenant, request.email, request.apiToken)
            logger.info { "JIRA_UI_SETUP: testApiToken tenant=${request.tenant} email=${request.email} ok=$ok" }
            AtlassianApiTokenTestResponseDto(success = ok, message = if (ok) null else "Unauthorized")
        } catch (e: Exception) {
            errorPublisher.publishError(
                message = "Jira token test failed: ${e.message}",
                stackTrace = e.stackTraceToString(),
            )
            throw e
        }

    @PostMapping("/save-api-token")
    override suspend fun saveApiToken(@RequestBody request: AtlassianApiTokenSaveRequestDto): AtlassianSetupStatusDto =
        try {
            val conn = jiraAuthService.saveApiToken(request.clientId, request.tenant, request.email, request.apiToken)
            logger.info { "JIRA_UI_SETUP: saveApiToken succeeded for client=${conn.clientId} tenant=${conn.tenant.value}" }
            fetchStatus(request.clientId)
        } catch (e: Exception) {
            errorPublisher.publishError(
                message = "Failed to save Jira API token: ${e.message}",
                stackTrace = e.stackTraceToString(),
            )
            throw e
        }

    @PostMapping("/begin-auth")
    override suspend fun beginAuth(@RequestBody request: AtlassianBeginAuthRequestDto): AtlassianBeginAuthResponseDto {
        val correlationId = UUID.randomUUID().toString()
        val authUrl = jiraAuthService.beginCloudOauth(request.clientId, request.tenant, request.redirectUri)

        val event =
            AtlassianAuthPromptEventDto(
                clientId = request.clientId,
                correlationId = correlationId,
                authUrl = authUrl,
                redirectUri = request.redirectUri,
            )
        val payload = json.encodeToString(event)
        sessionManager.broadcastToChannel(payload, WebSocketChannelTypeEnum.NOTIFICATIONS)
        logger.info { "JIRA_UI_SETUP: beginAuth published prompt for client=${request.clientId} correlationId=$correlationId" }

        return AtlassianBeginAuthResponseDto(correlationId = correlationId, authUrl = authUrl)
    }

    @PostMapping("/complete-auth")
    override suspend fun completeAuth(@RequestBody request: AtlassianCompleteAuthRequestDto): AtlassianSetupStatusDto {
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

    @PutMapping("/primary-project")
    override suspend fun setPrimaryProject(@RequestBody request: AtlassianProjectSelectionDto): AtlassianSetupStatusDto {
        jiraSelectionService.setPrimaryProject(ObjectId(request.clientId), JiraProjectKey(request.projectKey))
        logger.info { "JIRA_UI_SETUP: setPrimaryProject client=${request.clientId} project=${request.projectKey}" }
        return fetchStatus(request.clientId)
    }

    @PutMapping("/main-board")
    override suspend fun setMainBoard(@RequestBody request: AtlassianBoardSelectionDto): AtlassianSetupStatusDto {
        jiraSelectionService.setMainBoard(ObjectId(request.clientId), JiraBoardId(request.boardId))
        logger.info { "JIRA_UI_SETUP: setMainBoard client=${request.clientId} board=${request.boardId}" }
        return fetchStatus(request.clientId)
    }

    @PutMapping("/preferred-user")
    override suspend fun setPreferredUser(@RequestBody request: AtlassianUserSelectionDto): AtlassianSetupStatusDto {
        jiraSelectionService.setPreferredUser(ObjectId(request.clientId), JiraAccountId(request.accountId))
        logger.info { "JIRA_UI_SETUP: setPreferredUser client=${request.clientId} account=${request.accountId}" }
        return fetchStatus(request.clientId)
    }

    @PostMapping("/test-connection")
    override suspend fun testConnection(@RequestParam clientId: String): AtlassianSetupStatusDto =
        try {
            jiraConnectionService.testConnection(ObjectId(clientId))
            logger.info { "JIRA_UI_SETUP: testConnection OK for client=$clientId" }
            fetchStatus(clientId)
        } catch (e: Exception) {
            errorPublisher.publishError(
                message = "Jira test connection failed for client=$clientId: ${e.message}",
                stackTrace = e.stackTraceToString(),
            )
            // Even on failure, return current status (likely remains INVALID/UNKNOWN)
            fetchStatus(clientId)
        }

    @GetMapping("/projects")
    override suspend fun listProjects(@RequestParam clientId: String): List<AtlassianProjectRefDto> =
        try {
            val conn = jiraSelectionService.getConnection(ObjectId(clientId))
            val list = jiraApiClient.listProjects(conn)
            list.map {
                AtlassianProjectRefDto(key = it.first.value, name = it.second)
            }
        } catch (e: Exception) {
            errorPublisher.publishError(
                message = "Failed to list Jira projects: ${e.message}",
                stackTrace = e.stackTraceToString(),
            )
            throw e
        }

    @GetMapping("/boards")
    override suspend fun listBoards(
        @RequestParam clientId: String,
        @RequestParam(required = false) projectKey: String?,
    ): List<AtlassianBoardRefDto> =
        try {
            val conn = jiraSelectionService.getConnection(ObjectId(clientId))
            val boards =
                jiraApiClient.listBoards(
                    conn,
                    projectKey?.let {
                        JiraProjectKey(it)
                    },
                )
            boards.map {
                AtlassianBoardRefDto(id = it.first.value, name = it.second)
            }
        } catch (e: Exception) {
            errorPublisher.publishError(
                message = "Failed to list Jira boards: ${e.message}",
                stackTrace = e.stackTraceToString(),
            )
            throw e
        }

    private suspend fun fetchStatus(clientId: String): AtlassianSetupStatusDto =
        try {
            val conn = jiraSelectionService.getConnection(ObjectId(clientId))
            AtlassianSetupStatusDto(
                clientId = clientId,
                connected = true, // If connection exists, it's connected (API token doesn't expire)
                tenant = conn.tenant.value,
                email = conn.email,
                tokenPresent = conn.accessToken.isNotBlank(),
                apiToken = conn.accessToken.takeIf { it.isNotBlank() },
                primaryProject = conn.primaryProject?.value,
                mainBoard = conn.mainBoard?.value,
                preferredUser = conn.preferredUser?.value,
            )
        } catch (e: Exception) {
            // Not configured yet or invalid; return disconnected status
            AtlassianSetupStatusDto(
                clientId = clientId,
                connected = false,
            )
        }
}
