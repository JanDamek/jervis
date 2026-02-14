package com.jervis.repository

import com.jervis.di.NetworkModule
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.IBugTrackerSetupService
import com.jervis.service.IClientService
import com.jervis.service.ICodingAgentSettingsService
import com.jervis.service.IConnectionService
import com.jervis.service.IDeviceTokenService
import com.jervis.service.IIndexingQueueService
import com.jervis.service.IEnvironmentService
import com.jervis.service.IEnvironmentResourceService
import com.jervis.service.IErrorLogService
import com.jervis.service.IMeetingService
import com.jervis.service.ITranscriptCorrectionService
import com.jervis.service.INotificationService
import com.jervis.service.IPendingTaskService
import com.jervis.service.IPollingIntervalService
import com.jervis.service.IProjectGroupService
import com.jervis.service.IProjectService
import com.jervis.service.IRagSearchService
import com.jervis.service.ITaskSchedulingService
import com.jervis.service.IUserTaskService
import com.jervis.service.IWhisperSettingsService

/**
 * Main repository facade for Jervis application.
 * Provides unified access to all domain services via delegated getters.
 *
 * After RPC reconnection, [servicesProvider] returns the fresh Services instance,
 * so all service references are always current — no stale references.
 */
class JervisRepository(
    private val servicesProvider: () -> NetworkModule.Services,
) {
    val clients: IClientService get() = servicesProvider().clientService
    val projects: IProjectService get() = servicesProvider().projectService
    val projectGroups: IProjectGroupService get() = servicesProvider().projectGroupService
    val environments: IEnvironmentService get() = servicesProvider().environmentService
    val userTasks: IUserTaskService get() = servicesProvider().userTaskService
    val ragSearch: IRagSearchService get() = servicesProvider().ragSearchService
    val scheduledTasks: ITaskSchedulingService get() = servicesProvider().taskSchedulingService
    val agentOrchestrator: IAgentOrchestratorService get() = servicesProvider().agentOrchestratorService
    val errorLogs: IErrorLogService get() = servicesProvider().errorLogService
    val pendingTasks: IPendingTaskService get() = servicesProvider().pendingTaskService
    val connections: IConnectionService get() = servicesProvider().connectionService
    val notifications: INotificationService get() = servicesProvider().notificationService
    val bugTrackerSetup: IBugTrackerSetupService get() = servicesProvider().bugTrackerSetupService
    val codingAgents: ICodingAgentSettingsService get() = servicesProvider().codingAgentSettingsService
    val whisperSettings: IWhisperSettingsService get() = servicesProvider().whisperSettingsService
    val pollingIntervals: IPollingIntervalService get() = servicesProvider().pollingIntervalService
    val meetings: IMeetingService get() = servicesProvider().meetingService
    val transcriptCorrections: ITranscriptCorrectionService get() = servicesProvider().transcriptCorrectionService
    val deviceTokens: IDeviceTokenService get() = servicesProvider().deviceTokenService
    val indexingQueue: IIndexingQueueService get() = servicesProvider().indexingQueueService
    val environmentResources: IEnvironmentResourceService get() = servicesProvider().environmentResourceService
}
