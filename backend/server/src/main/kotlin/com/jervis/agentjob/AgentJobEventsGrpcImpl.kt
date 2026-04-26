package com.jervis.agentjob

import com.jervis.contracts.server.AgentJobEventsServiceGrpcKt
import com.jervis.contracts.server.AgentJobEventsSubscribeRequest
import com.jervis.contracts.server.AgentJobStateChangedEvent
import com.jervis.dto.events.JervisEvent
import com.jervis.rpc.NotificationRpcImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * gRPC bridge that converts the kRPC-level [JervisEvent.AgentJobStateChanged]
 * broadcast (owned by [NotificationRpcImpl]) into a server-streaming gRPC
 * call. Subscribers — typically the Python orchestrator's per-client
 * session loops — open one long-lived stream per session and inject the
 * arriving events as `[agent-update]` system messages into the LLM queue.
 *
 * Closes the polling discipline introduced in commit `bbe953405`: agents
 * see state transitions automatically in the next LLM turn, no
 * `get_agent_job_status` polling required.
 *
 * Emit fan-out happens on the kRPC side ([NotificationRpcImpl.
 * emitAgentJobStateChanged] iterates every connected client's
 * `eventStreams` entry); this servicer just registers as another
 * subscriber under a unique transient id and filters for the one event
 * type it cares about. When the gRPC stream is cancelled (client
 * disconnect / session teardown), the upstream `Flow.collect` cancels
 * naturally and the subscriber id falls out of `eventStreams` lazily —
 * stale entries are tolerated because emit just iterates the map.
 */
@Component
class AgentJobEventsGrpcImpl(
    private val notificationRpc: NotificationRpcImpl,
) : AgentJobEventsServiceGrpcKt.AgentJobEventsServiceCoroutineImplBase() {

    private val logger = KotlinLogging.logger {}

    override fun subscribe(request: AgentJobEventsSubscribeRequest): Flow<AgentJobStateChangedEvent> {
        val subscriberId = "agent-events-grpc-${UUID.randomUUID()}"
        val clientFilter = request.clientId.takeIf { it.isNotBlank() }
        logger.info { "AgentJobEvents.Subscribe open | subscriber=$subscriberId clientFilter=${clientFilter ?: "<all>"}" }

        return notificationRpc
            .subscribeToEvents(subscriberId)
            .filterIsInstance<JervisEvent.AgentJobStateChanged>()
            .filter { evt -> clientFilter == null || evt.clientId == clientFilter }
            .map { evt -> evt.toProto() }
    }

    private fun JervisEvent.AgentJobStateChanged.toProto(): AgentJobStateChangedEvent =
        AgentJobStateChangedEvent.newBuilder()
            .setAgentJobId(agentJobId)
            .setFlavor(flavor)
            .setState(state)
            .setTitle(title)
            .setClientId(clientId.orEmpty())
            .setProjectId(projectId.orEmpty())
            .setResourceId(resourceId.orEmpty())
            .setGitBranch(gitBranch.orEmpty())
            .setGitCommitSha(gitCommitSha.orEmpty())
            .setResultSummary(resultSummary.orEmpty())
            .setErrorMessage(errorMessage.orEmpty())
            .addAllArtifacts(artifacts)
            .setTransitionedAt(transitionedAt)
            .setStartedAt(startedAt.orEmpty())
            .setCompletedAt(completedAt.orEmpty())
            .build()
}
