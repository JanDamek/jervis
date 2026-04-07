package com.jervis.di

import com.jervis.service.agent.IAgentOrchestratorService
import com.jervis.service.agent.IAgentQuestionService
import com.jervis.service.agent.IAutoResponseSettingsService
import com.jervis.service.chat.IChatService
import com.jervis.service.client.IClientService
import com.jervis.service.connection.IConnectionService
import com.jervis.service.connection.IPollingIntervalService
import com.jervis.service.environment.IEnvironmentResourceService
import com.jervis.service.environment.IEnvironmentService
import com.jervis.service.error.IErrorLogService
import com.jervis.service.git.IGpgCertificateService
import com.jervis.service.guidelines.IGuidelinesService
import com.jervis.service.finance.IFinancialService
import com.jervis.service.meeting.IJobLogsService
import com.jervis.service.meeting.IMeetingHelperService
import com.jervis.service.meeting.IMeetingService
import com.jervis.service.meeting.ISpeakerService
import com.jervis.service.meeting.ITranscriptCorrectionService
import com.jervis.service.notification.IDeviceTokenService
import com.jervis.service.notification.INotificationService
import com.jervis.service.preferences.IOpenRouterSettingsService
import com.jervis.service.preferences.ISystemConfigService
import com.jervis.service.project.IProjectService
import com.jervis.service.projectgroup.IProjectGroupService
import com.jervis.service.rag.IRagSearchService
import com.jervis.service.task.IIndexingQueueService
import com.jervis.service.task.IPendingTaskService
import com.jervis.service.task.ITaskGraphService
import com.jervis.service.task.ITaskSchedulingService
import com.jervis.service.task.IUserTaskService
import com.jervis.service.timetracking.ITimeTrackingService
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Main repository facade for Jervis application.
 * Provides unified access to all domain services via delegated getters.
 *
 * After RPC reconnection, [servicesProvider] returns the fresh Services instance,
 * so all service references are always current — no stale references.
 *
 * ## Two call styles
 *
 * **1. Direct accessor** (backwards-compatible): `repository.chat.sendMessage(...)`
 *    - Synchronously fetches current services; throws [OfflineException] if disconnected.
 *    - Fast, zero-overhead. Use for calls that can fail fast without waiting.
 *
 * **2. Resilient [call]** (new): `repository.call { services -> services.chatService.sendMessage(...) }`
 *    - Waits up to [Duration] for Connected state.
 *    - Wraps the call in a timeout.
 *    - On connection-lost errors, automatically triggers reconnect and throws [OfflineException].
 *    - Use for user-initiated actions where you want transparent wait-for-reconnect.
 *
 * The resilient flavor requires a [connectionManager] to be supplied. The legacy lambda
 * constructor remains for backwards compat (e.g., mobile/iOS construction sites that
 * still use it) — in that mode, [call] falls back to direct invocation without
 * reconnect semantics.
 */
class JervisRepository private constructor(
    private val servicesProvider: () -> NetworkModule.Services,
    private val connectionManager: RpcConnectionManager?,
) {
    /** Legacy lambda-based constructor — kept for mobile/iOS/test call sites. */
    constructor(servicesProvider: () -> NetworkModule.Services) :
        this(servicesProvider, connectionManager = null)

    /**
     * Preferred constructor — delegates to [RpcConnectionManager] for both service lookup
     * and resilient [call] behavior.
     */
    constructor(connectionManager: RpcConnectionManager) : this(
        servicesProvider = { connectionManager.getServices() ?: throw OfflineException() },
        connectionManager = connectionManager,
    )

    // ─── Direct accessors (34 services) ─────────────────────────────
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
    val meetingHelper: IMeetingHelperService get() = servicesProvider().meetingHelperService
    val finance: IFinancialService get() = servicesProvider().financialService
    val taskGraphs: ITaskGraphService get() = servicesProvider().taskGraphService
    val jobLogs: IJobLogsService get() = servicesProvider().jobLogsService
    val agentQuestions: IAgentQuestionService get() = servicesProvider().agentQuestionService
    val autoResponseSettings: IAutoResponseSettingsService get() = servicesProvider().autoResponseSettingsService
    val timeTracking: ITimeTrackingService get() = servicesProvider().timeTrackingService

    // ─── Resilient call wrapper ─────────────────────────────────────

    /**
     * Resilient one-shot RPC call. Waits for connection (bounded by [timeout]),
     * runs [block] under `withTimeout`, on connection-lost errors triggers reconnect
     * and throws [OfflineException].
     *
     * Use for user-initiated actions: `sendMessage`, `respondToTask`, `dismissTask`, etc.
     *
     * If this repository was constructed with the legacy lambda constructor (no
     * [RpcConnectionManager]), falls back to direct invocation — no reconnect semantics,
     * but the call will throw [OfflineException] from [servicesProvider] if disconnected.
     */
    suspend fun <T> call(
        timeout: Duration = 10.seconds,
        block: suspend (NetworkModule.Services) -> T,
    ): T {
        val manager = connectionManager
        return if (manager != null) {
            manager.rpcCall(timeout, block)
        } else {
            // Legacy fallback — direct invocation, no wait, no reconnect.
            block(servicesProvider())
        }
    }
}
