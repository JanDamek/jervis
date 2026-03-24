package com.jervis.repository

import com.jervis.di.NetworkModule
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.IClientService
import com.jervis.service.IGpgCertificateService
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
import com.jervis.service.IChatService
import com.jervis.service.IGuidelinesService
import com.jervis.service.IOpenRouterSettingsService
import com.jervis.service.ISpeakerService
import com.jervis.service.ISystemConfigService
import com.jervis.service.IJobLogsService
import com.jervis.service.ITaskGraphService
import com.jervis.service.IAgentQuestionService
import com.jervis.service.IAutoResponseSettingsService

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
    val gpgCertificates: IGpgCertificateService get() = servicesProvider().gpgCertificateService
    val pollingIntervals: IPollingIntervalService get() = servicesProvider().pollingIntervalService
    val meetings: IMeetingService get() = servicesProvider().meetingService
    val transcriptCorrections: ITranscriptCorrectionService get() = servicesProvider().transcriptCorrectionService
    val deviceTokens: IDeviceTokenService get() = servicesProvider().deviceTokenService
    val indexingQueue: IIndexingQueueService get() = servicesProvider().indexingQueueService
    val environmentResources: IEnvironmentResourceService get() = servicesProvider().environmentResourceService
    val systemConfig: ISystemConfigService get() = servicesProvider().systemConfigService
    val chat: IChatService get() = servicesProvider().chatService
    val guidelines: IGuidelinesService get() = servicesProvider().guidelinesService
    val openRouterSettings: IOpenRouterSettingsService get() = servicesProvider().openRouterSettingsService
    val speakers: ISpeakerService get() = servicesProvider().speakerService
    val taskGraphs: ITaskGraphService get() = servicesProvider().taskGraphService
    val jobLogs: IJobLogsService get() = servicesProvider().jobLogsService
    val agentQuestions: IAgentQuestionService get() = servicesProvider().agentQuestionService
    val autoResponseSettings: IAutoResponseSettingsService get() = servicesProvider().autoResponseSettingsService
}
