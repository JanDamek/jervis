package com.jervis.repository

import com.jervis.service.IBugTrackerSetupService
import com.jervis.service.IIntegrationSettingsService
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.IClientService
import com.jervis.service.IConnectionService
import com.jervis.service.IErrorLogService
import com.jervis.service.IGitConfigurationService
import com.jervis.service.IPendingTaskService
import com.jervis.service.INotificationService
import com.jervis.service.IProjectService
import com.jervis.service.IRagSearchService
import com.jervis.service.ITaskSchedulingService
import com.jervis.service.IUserTaskService

/**
 * Main repository facade for Jervis application
 * Provides unified access to all domain repositories
 */
class JervisRepository(
    clientService: IClientService,
    projectService: IProjectService,
    userTaskService: IUserTaskService,
    ragSearchService: IRagSearchService,
    taskSchedulingService: ITaskSchedulingService,
    agentOrchestratorService: IAgentOrchestratorService,
    errorLogService: IErrorLogService,
    gitConfigurationService: IGitConfigurationService,
    pendingTaskService: IPendingTaskService,
    connectionService: IConnectionService,
    notificationService: INotificationService,
    bugTrackerSetupService: IBugTrackerSetupService,
    integrationSettingsService: IIntegrationSettingsService,
) {
    val clients: ClientRepository = ClientRepository(clientService)
    val projects: ProjectRepository = ProjectRepository(projectService)
    val userTasks: UserTaskRepository = UserTaskRepository(userTaskService)
    val ragSearch: RagSearchRepository = RagSearchRepository(ragSearchService)
    val scheduledTasks: ScheduledTaskRepository =
        ScheduledTaskRepository(taskSchedulingService, agentOrchestratorService)
    val errorLogs: ErrorLogRepository = ErrorLogRepository(errorLogService)
    val agentChat: AgentChatRepository = AgentChatRepository(agentOrchestratorService)
    val gitConfiguration: GitConfigurationRepository = GitConfigurationRepository(gitConfigurationService)
    val pendingTasks: PendingTaskRepository = PendingTaskRepository(pendingTaskService)
    val connections: ConnectionRepository = ConnectionRepository(connectionService)
    val notifications: NotificationRepository = NotificationRepository(notificationService)
    val bugTrackerSetup: BugTrackerSetupRepository = BugTrackerSetupRepository(bugTrackerSetupService)
    val integrationSettings: IntegrationSettingsRepository = IntegrationSettingsRepository(integrationSettingsService)
}
