package com.jervis.repository

import com.jervis.service.*

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
    integrationSettingsService: IIntegrationSettingsService,
    gitConfigurationService: IGitConfigurationService,
    jiraSetupService: IJiraSetupService,
    emailAccountService: IEmailAccountService,
    indexingStatusService: IIndexingStatusService,
) {
    val clients: ClientRepository = ClientRepository(clientService)
    val projects: ProjectRepository = ProjectRepository(projectService)
    val userTasks: UserTaskRepository = UserTaskRepository(userTaskService)
    val ragSearch: RagSearchRepository = RagSearchRepository(ragSearchService)
    val scheduledTasks: ScheduledTaskRepository = ScheduledTaskRepository(taskSchedulingService, agentOrchestratorService)
    val errorLogs: ErrorLogRepository = ErrorLogRepository(errorLogService)
    val agentChat: AgentChatRepository = AgentChatRepository(agentOrchestratorService)
    val integrationSettings: IntegrationSettingsRepository = IntegrationSettingsRepository(integrationSettingsService)
    val gitConfiguration: GitConfigurationRepository = GitConfigurationRepository(gitConfigurationService)
    val jiraSetup: JiraSetupRepository = JiraSetupRepository(jiraSetupService)
    val emailAccounts: EmailAccountRepository = EmailAccountRepository(emailAccountService)
    val indexingStatus: IndexingStatusRepository = IndexingStatusRepository(indexingStatusService)
}
