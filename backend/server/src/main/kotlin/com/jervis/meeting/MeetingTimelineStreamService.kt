package com.jervis.meeting

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.meeting.MeetingTimelineDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Push-only meeting timeline stream. Mirrors [SidebarStreamService]:
 * one [MutableSharedFlow] per `(clientId, projectId)` scope, replay=1
 * so a fresh subscriber gets the latest snapshot immediately. Every
 * mutation of a `MeetingDocument` on the server calls [invalidate]
 * with the matching scope; UI never pulls — see `docs/guidelines.md` §9.
 */
@Service
class MeetingTimelineStreamService(
    @Lazy private val meetingRpcImpl: MeetingRpcImpl,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Key format: "{clientId|""}:{projectId|""}"
    private val flows = ConcurrentHashMap<String, MutableSharedFlow<MeetingTimelineDto>>()

    fun subscribe(clientId: String?, projectId: String?): Flow<MeetingTimelineDto> {
        val (cid, pid) = normalize(clientId, projectId)
        val key = scopeKey(cid, pid)
        val flow = flows.computeIfAbsent(key) {
            MutableSharedFlow(replay = 1, extraBufferCapacity = 16)
        }
        return flow.onSubscription {
            if (flow.replayCache.isEmpty()) {
                emit(buildSnapshot(cid, pid))
            }
        }
    }

    /**
     * Rebuild and emit the timeline for the (clientId, projectId) scope
     * AND the global (null, null) scope, because the unscoped view is
     * a superset of every client's feed. Called from every write path
     * in [MeetingRpcImpl] that mutates a MeetingDocument.
     */
    fun invalidate(clientId: ClientId?, projectId: ProjectId?) {
        val cid = clientId?.toString()
        val pid = projectId?.toString()
        scope.launch {
            try {
                emitFor(cid, pid)
                // Project-scoped stream also implies the client-only view
                // has new data — refresh that too.
                if (pid != null) emitFor(cid, null)
                // Global feed (no filter) always needs refreshing.
                if (cid != null) emitFor(null, null)
            } catch (e: Exception) {
                logger.warn(e) { "MEETING_TIMELINE_INVALIDATE_FAILED: client=$cid project=$pid" }
            }
        }
    }

    private suspend fun emitFor(clientId: String?, projectId: String?) {
        val key = scopeKey(clientId, projectId)
        val flow = flows[key] ?: return // no subscribers → skip work
        flow.emit(buildSnapshot(clientId, projectId))
    }

    private suspend fun buildSnapshot(clientId: String?, projectId: String?): MeetingTimelineDto =
        meetingRpcImpl.getMeetingTimeline(clientId, projectId)

    private fun normalize(clientId: String?, projectId: String?): Pair<String?, String?> {
        val cid = clientId?.takeIf { it.isNotBlank() && it != "__global__" }
        val pid = projectId?.takeIf { it.isNotBlank() }
        return cid to pid
    }

    private fun scopeKey(clientId: String?, projectId: String?): String =
        "${clientId.orEmpty()}:${projectId.orEmpty()}"
}
