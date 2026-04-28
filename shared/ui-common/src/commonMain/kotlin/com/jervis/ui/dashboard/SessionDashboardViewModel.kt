package com.jervis.ui.dashboard

import com.jervis.di.RpcConnectionManager
import com.jervis.dto.dashboard.DashboardSnapshotDto
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * ViewModel for the orchestrator session [SessionDashboardScreen].
 *
 * Subscribes to a single push-only kRPC flow
 * (`IDashboardService.subscribeSessionSnapshot`) wrapped in
 * [RpcConnectionManager.resilientFlow] so the stream survives reconnects.
 * No polling, no refresh button — rule #9 (push-only UI surfaces).
 */
class SessionDashboardViewModel(
    private val connectionManager: RpcConnectionManager,
) {
    private val scope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            if (e !is CancellationException) {
                println("SessionDashboardViewModel: uncaught exception ${e::class.simpleName}: ${e.message}")
            }
        },
    )

    private val _snapshot = MutableStateFlow<DashboardSnapshotDto?>(null)
    val snapshot: StateFlow<DashboardSnapshotDto?> = _snapshot.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                connectionManager.resilientFlow { services ->
                    services.dashboardService.subscribeSessionSnapshot()
                }.collect { snap ->
                    _snapshot.value = snap
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                println("SessionDashboardViewModel.subscribeSessionSnapshot failed: ${e.message}")
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
    }
}
