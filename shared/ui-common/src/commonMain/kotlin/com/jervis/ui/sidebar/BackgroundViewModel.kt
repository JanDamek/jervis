package com.jervis.ui.sidebar

import com.jervis.di.JervisRepository
import com.jervis.di.RpcConnectionManager
import com.jervis.dto.agentjob.AgentJobListSnapshot
import com.jervis.dto.agentjob.AgentNarrativeEvent
import com.jervis.dto.vnc.VncSessionSnapshot
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
 * ViewModel for the sidebar Background section + click-expand detail
 * panel.
 *
 * Two top-level streams (rule #9 — push-only, no polling):
 *  - `subscribeAgentJobs` → [agentJobs]: replay-1 [AgentJobListSnapshot]
 *    grouping running / queued / waitingUser / recent. Re-emits whenever
 *    the server-side `JervisEvent.AgentJobStateChanged` push fires.
 *  - `subscribeActiveSessions` → [vncSessions]: list of currently active
 *    VNC sessions, polled server-side every 5 s with duplicate
 *    suppression.
 *
 * Detail panel: when the user expands a single agent-job row,
 * [openNarrative] starts a third subscription against
 * `subscribeAgentNarrative(agentJobId)` whose events accumulate in
 * [activeNarrative]. [closeNarrative] cancels it. Only one narrative
 * stream is live at a time — the panel is single-instance.
 */
class BackgroundViewModel(
    private val repository: JervisRepository,
    private val connectionManager: RpcConnectionManager,
) {
    private val scope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            if (e !is CancellationException) {
                println("BackgroundViewModel: uncaught exception ${e::class.simpleName}: ${e.message}")
            }
        },
    )

    private val _agentJobs = MutableStateFlow<AgentJobListSnapshot?>(null)
    val agentJobs: StateFlow<AgentJobListSnapshot?> = _agentJobs.asStateFlow()

    private val _vncSessions = MutableStateFlow<List<VncSessionSnapshot>>(emptyList())
    val vncSessions: StateFlow<List<VncSessionSnapshot>> = _vncSessions.asStateFlow()

    private val _activeNarrativeAgentJobId = MutableStateFlow<String?>(null)
    val activeNarrativeAgentJobId: StateFlow<String?> = _activeNarrativeAgentJobId.asStateFlow()

    /** Accumulated narrative events for the currently expanded job (replay-from-zero). */
    private val _activeNarrative = MutableStateFlow<List<AgentNarrativeEvent>>(emptyList())
    val activeNarrative: StateFlow<List<AgentNarrativeEvent>> = _activeNarrative.asStateFlow()

    private var agentJobsJob: Job? = null
    private var vncJob: Job? = null
    private var narrativeJob: Job? = null

    fun start() {
        if (agentJobsJob?.isActive == true) return
        agentJobsJob = scope.launch {
            try {
                connectionManager.resilientFlow { services ->
                    services.agentJobService.subscribeAgentJobs(
                        clientId = null,
                        projectId = null,
                        includeTerminalForHours = 24,
                    )
                }.collect { snapshot ->
                    _agentJobs.value = snapshot
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                println("BackgroundViewModel.subscribeAgentJobs failed: ${e.message}")
            }
        }
        vncJob = scope.launch {
            try {
                connectionManager.resilientFlow { services ->
                    services.vncService.subscribeActiveSessions(clientId = null)
                }.collect { sessions ->
                    _vncSessions.value = sessions
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                println("BackgroundViewModel.subscribeActiveSessions failed: ${e.message}")
            }
        }
    }

    fun openNarrative(agentJobId: String) {
        if (_activeNarrativeAgentJobId.value == agentJobId && narrativeJob?.isActive == true) return
        closeNarrative()
        _activeNarrativeAgentJobId.value = agentJobId
        _activeNarrative.value = emptyList()
        narrativeJob = scope.launch {
            try {
                connectionManager.resilientFlow { services ->
                    services.agentJobService.subscribeAgentNarrative(agentJobId)
                }.collect { event ->
                    _activeNarrative.value = _activeNarrative.value + event
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                println("BackgroundViewModel.subscribeAgentNarrative failed: ${e.message}")
            }
        }
    }

    fun closeNarrative() {
        narrativeJob?.cancel()
        narrativeJob = null
        _activeNarrativeAgentJobId.value = null
        _activeNarrative.value = emptyList()
    }

    fun stop() {
        agentJobsJob?.cancel(); agentJobsJob = null
        vncJob?.cancel(); vncJob = null
        narrativeJob?.cancel(); narrativeJob = null
    }
}
