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
) {
    val clients: ClientRepository = ClientRepository(clientService)
    val projects: ProjectRepository = ProjectRepository(projectService)
    val userTasks: UserTaskRepository = UserTaskRepository(userTaskService)
    val ragSearch: RagSearchRepository = RagSearchRepository(ragSearchService)
    val scheduledTasks: ScheduledTaskRepository = ScheduledTaskRepository(taskSchedulingService, agentOrchestratorService)
    val errorLogs: ErrorLogRepository = ErrorLogRepository(errorLogService)
    val agentChat: AgentChatRepository = AgentChatRepository(agentOrchestratorService)
}
