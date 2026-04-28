package com.jervis.dashboard

import com.jervis.dto.dashboard.ActiveSessionDto
import com.jervis.dto.dashboard.DashboardSnapshotDto
import com.jervis.dto.dashboard.EvictionRecordDto
import com.jervis.infrastructure.grpc.OrchestratorDashboardGrpcClient
import com.jervis.service.dashboard.IDashboardService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Server-side push-flow for the desktop Dashboard screen.
 *
 * Architecture:
 * ```
 * UI (Compose) ←kRPC subscribeSessionSnapshot← jervis-server ←gRPC GetSessionSnapshot← orchestrator (Python)
 *      push                                          poll 5 s                                in-process broker
 * ```
 *
 * The orchestrator's SessionBroker is in-process Python state — no
 * server-streaming push channel exists today. The Kotlin server pulls
 * every 5 s and republishes via a `MutableSharedFlow(replay=1)` so the
 * UI ↔ server contract stays push-only (rule #9). Pull cadence is an
 * internal implementation detail.
 */
@Component
class DashboardRpcImpl(
    private val orchestratorDashboardGrpc: OrchestratorDashboardGrpcClient,
) : IDashboardService {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val flow = MutableSharedFlow<DashboardSnapshotDto>(replay = 1, extraBufferCapacity = 4)
    private var pollJob: Job? = null

    @PostConstruct
    fun start() {
        pollJob = scope.launch {
            while (isActive) {
                runCatching {
                    val resp = orchestratorDashboardGrpc.getSessionSnapshot()
                    if (resp.ok) {
                        flow.emit(resp.toDto())
                    } else {
                        logger.debug { "DASHBOARD_SNAPSHOT_NOT_OK error=${resp.error}" }
                    }
                }.onFailure { e ->
                    // Connection-level errors → retry, not FAILED. The
                    // orchestrator pod might be restarting; the next tick
                    // will pick it back up.
                    logger.debug(e) { "DASHBOARD_SNAPSHOT_PULL_FAIL: ${e.message}" }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        logger.info { "DashboardRpcImpl started — polling orchestrator every ${POLL_INTERVAL_MS}ms" }
    }

    @PreDestroy
    fun stop() {
        pollJob?.cancel()
        pollJob = null
        scope.cancel()
        logger.info { "DashboardRpcImpl stopped" }
    }

    override fun subscribeSessionSnapshot(): Flow<DashboardSnapshotDto> = flow

    private fun com.jervis.contracts.orchestrator.SessionSnapshotResponse.toDto(): DashboardSnapshotDto =
        DashboardSnapshotDto(
            activeCount = activeCount,
            cap = cap,
            paused = paused,
            sessions = sessionsList.map { it.toDto() },
            agentJobHolds = agentJobHoldsMap.toMap(),
            recentEvictions = recentEvictionsList.map { it.toDto() },
        )

    private fun com.jervis.contracts.orchestrator.ActiveSession.toDto(): ActiveSessionDto =
        ActiveSessionDto(
            scope = scope,
            sessionId = sessionId,
            clientId = clientId,
            projectId = projectId,
            cumulativeTokens = cumulativeTokens,
            idleSeconds = idleSeconds,
            compactInProgress = compactInProgress,
            lastCompactAgeSeconds = lastCompactAgeSeconds,
        )

    private fun com.jervis.contracts.orchestrator.EvictionRecord.toDto(): EvictionRecordDto =
        EvictionRecordDto(scope = scope, reason = reason, ts = ts)

    companion object {
        private const val POLL_INTERVAL_MS = 5_000L
    }
}
