package com.jervis.ui.sidebar

import com.jervis.di.JervisRepository
import com.jervis.di.RpcConnectionManager
import com.jervis.dto.agentjob.AgentJobListSnapshot
import com.jervis.dto.agentjob.AgentJobSnapshot
import com.jervis.dto.agentjob.AgentNarrativeEvent
import com.jervis.dto.vnc.VncSessionSnapshot
import com.jervis.ui.util.openUrlInBrowser
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

    /**
     * PR4 — count of Claude proposals in stage AWAITING_APPROVAL across
     * all clients. Surfaced in the sidebar Background section as
     * "Návrhy ke schválení (N)". Push-only via
     * [com.jervis.service.proposal.IProposalActionService.subscribePendingProposalsCount]
     * (replay=1 on the server).
     */
    private val _pendingProposalsCount = MutableStateFlow(0)
    val pendingProposalsCount: StateFlow<Int> = _pendingProposalsCount.asStateFlow()

    /**
     * Currently expanded agent job snapshot — drives the in-chat detail
     * panel mount. Set by [openNarrative], cleared by [closeNarrative]
     * (or implicitly when [openVncEmbed] takes over the chat area).
     */
    private val _activeJobSnapshot = MutableStateFlow<AgentJobSnapshot?>(null)
    val activeJobSnapshot: StateFlow<AgentJobSnapshot?> = _activeJobSnapshot.asStateFlow()

    /** Accumulated narrative events for the currently expanded job (replay-from-zero). */
    private val _activeNarrative = MutableStateFlow<List<AgentNarrativeEvent>>(emptyList())
    val activeNarrative: StateFlow<List<AgentNarrativeEvent>> = _activeNarrative.asStateFlow()

    /**
     * Currently embedded VNC session — drives the in-chat WebView mount.
     * vncUrl is minted on click via IConnectionService.getBrowserSessionStatus
     * (same path the Settings VNC button uses). Mutually exclusive with
     * [activeJobSnapshot] — both share the chat content area.
     */
    private val _activeVnc = MutableStateFlow<ActiveVnc?>(null)
    val activeVnc: StateFlow<ActiveVnc?> = _activeVnc.asStateFlow()

    /**
     * Snapshot of an embedded VNC session displayed in the chat content
     * area. The token in `vncUrl` is one-shot — first request to the
     * vnc-router consumes it and sets a `vnc_session` cookie.
     */
    data class ActiveVnc(
        val connectionId: String,
        val connectionLabel: String,
        val vncUrl: String,
    )

    private var agentJobsJob: Job? = null
    private var vncJob: Job? = null
    private var narrativeJob: Job? = null
    private var proposalsCountJob: Job? = null

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
        // PR4 — pending Claude proposals count (sidebar badge)
        proposalsCountJob = scope.launch {
            try {
                connectionManager.resilientFlow { services ->
                    services.proposalActionService.subscribePendingProposalsCount()
                }.collect { count ->
                    _pendingProposalsCount.value = count
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                println("BackgroundViewModel.subscribePendingProposalsCount failed: ${e.message}")
            }
        }
    }

    fun openNarrative(snapshot: AgentJobSnapshot) {
        if (_activeJobSnapshot.value?.id == snapshot.id && narrativeJob?.isActive == true) {
            // Already open for this job — keep the panel state.
            return
        }
        closeNarrative()
        // Switching to narrative panel implicitly closes any open VNC embed
        // (single chat content area).
        _activeVnc.value = null
        _activeJobSnapshot.value = snapshot
        _activeNarrative.value = emptyList()
        narrativeJob = scope.launch {
            try {
                connectionManager.resilientFlow { services ->
                    services.agentJobService.subscribeAgentNarrative(snapshot.id)
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
        _activeJobSnapshot.value = null
        _activeNarrative.value = emptyList()
    }

    /**
     * Mint a fresh VNC URL via the existing
     * `IConnectionService.getBrowserSessionStatus(connectionId)` and either
     * embed it inline (mode = `Embed`) or open in the user's default
     * browser (mode = `External`). The mint path is shared with the
     * Settings VNC button so both flows produce identical one-shot URLs.
     */
    fun openVncEmbed(snapshot: VncSessionSnapshot) {
        if (!snapshot.requiresMint) return  // placeholder (WhatsApp pre-migration)
        scope.launch {
            val url = mintVncUrl(snapshot.connectionId) ?: return@launch
            // Switching to VNC embed implicitly closes any open narrative
            // panel.
            closeNarrative()
            _activeVnc.value = ActiveVnc(
                connectionId = snapshot.connectionId,
                connectionLabel = snapshot.connectionLabel,
                vncUrl = url,
            )
        }
    }

    fun openVncExternal(snapshot: VncSessionSnapshot) {
        if (!snapshot.requiresMint) return
        scope.launch {
            val url = mintVncUrl(snapshot.connectionId) ?: return@launch
            openUrlInBrowser(url)
        }
    }

    fun closeVncEmbed() {
        _activeVnc.value = null
    }

    private suspend fun mintVncUrl(connectionId: String): String? {
        val status = try {
            repository.call { it.connectionService.getBrowserSessionStatus(connectionId) }
        } catch (e: Exception) {
            println("BackgroundViewModel.mintVncUrl failed for $connectionId: ${e.message}")
            return null
        }
        return status.vncUrl?.takeIf { it.isNotBlank() }
    }

    fun stop() {
        agentJobsJob?.cancel(); agentJobsJob = null
        vncJob?.cancel(); vncJob = null
        narrativeJob?.cancel(); narrativeJob = null
        proposalsCountJob?.cancel(); proposalsCountJob = null
    }
}
