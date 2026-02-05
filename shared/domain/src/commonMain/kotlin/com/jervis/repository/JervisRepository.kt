package com.jervis.repository

import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.IBugTrackerSetupService
import com.jervis.service.IClientService
import com.jervis.service.IConnectionService
import com.jervis.service.IErrorLogService
import com.jervis.service.INotificationService
import com.jervis.service.IPendingTaskService
import com.jervis.service.IProjectService
import com.jervis.service.IRagSearchService
import com.jervis.service.ITaskSchedulingService
import com.jervis.service.IUserTaskService

/**
 * Main repository facade for Jervis application
 * Provides unified access to all domain services directly.
 * Formerly wrapped services in Repositories, now exposes services directly.
 */
class JervisRepository(
    val clients: IClientService,
    val projects: IProjectService,
    val userTasks: IUserTaskService,
    val ragSearch: IRagSearchService,
    val scheduledTasks: ITaskSchedulingService,
    val agentOrchestrator: IAgentOrchestratorService,
    val errorLogs: IErrorLogService,
    val pendingTasks: IPendingTaskService,
    val connections: IConnectionService,
    val notifications: INotificationService,
    val bugTrackerSetup: IBugTrackerSetupService,
)
