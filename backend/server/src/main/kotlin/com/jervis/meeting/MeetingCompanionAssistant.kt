package com.jervis.meeting

import com.jervis.dto.meeting.HelperMessageDto
import com.jervis.dto.meeting.HelperMessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Live meeting assistant backed by the Python orchestrator's Claude companion.
 *
 * Lifecycle per meeting:
 * 1. `start(meetingId, …)` — starts a persistent companion session, seeds the
 *    brief, and launches an outbox consumer coroutine that converts Claude's
 *    suggestion/answer events into `HelperMessageDto` pushed through the
 *    existing `MeetingHelperService` (Desktop + mobile devices see them via
 *    the standard RPC event stream — no UI changes needed).
 * 2. `forwardSegment(meetingId, text)` — every freshly transcribed whisper
 *    segment is pushed into the session inbox with `type=meeting`.
 * 3. `stop(meetingId)` — sends END marker + deletes the Job.
 *
 * The assistant forwards transcripts regardless of urgency-detector regex
 * matches — Claude decides what to surface. The existing
 * `MeetingUrgencyDetector` remains a complementary fast-path for
 * name-mentions / direct questions (regex, zero latency).
 */
@Service
class MeetingCompanionAssistant(
    private val companionClient: OrchestratorCompanionClient,
    private val helperService: MeetingHelperService,
) {
    private data class ActiveSession(
        val sessionId: String,
        val meetingId: String,
        val streamJob: Job,
    )

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)
    private val active = ConcurrentHashMap<String, ActiveSession>()

    fun isActive(meetingId: String): Boolean = active.containsKey(meetingId)

    /** True when at least one meeting has a live companion session.
     *  Used to switch whisper into live-probe priority mode. */
    fun hasAnyActiveSession(): Boolean = active.isNotEmpty()

    /** Start a companion session for this meeting. Idempotent — returns existing
     *  session if active in-memory; after a server restart it re-attaches to an
     *  already-running K8s Job via the same session_id (orchestrator returns the
     *  existing workspace instead of creating a new one). */
    suspend fun start(
        meetingId: String,
        clientId: String,
        projectId: String?,
        meetingTitle: String?,
        userName: String?,
    ): String {
        active[meetingId]?.let { return it.sessionId }

        val brief = buildBrief(meetingId, meetingTitle, userName)
        val sessionId = "mtg-${meetingId.take(12)}"
        // startSession is idempotent on the orchestrator side: if a Job with the
        // same session_id already exists the call returns the existing workspace.
        // We swallow conflict errors and just reattach the outbox consumer.
        val resp = runCatching {
            companionClient.startSession(
                OrchestratorCompanionClient.SessionStartRequest(
                    session_id = sessionId,
                    brief = brief,
                    client_id = clientId,
                    project_id = projectId,
                    language = "cs",
                    context = buildMap {
                        put("meetingId", meetingId)
                        meetingTitle?.let { put("meetingTitle", it) }
                        userName?.let { put("userName", it) }
                    },
                ),
            )
        }.getOrElse { e ->
            logger.warn(e) { "startSession failed, attempting to re-attach existing Job for meeting=$meetingId" }
            OrchestratorCompanionClient.SessionStartResponse(
                job_name = "jervis-companion-s-${sessionId.take(12)}",
                workspace_path = "/opt/jervis/data/companion/$sessionId",
                session_id = sessionId,
            )
        }

        val streamJob = scope.launch {
            runCatching {
                companionClient.streamOutbox(resp.session_id, maxAgeSeconds = 45).collect { event ->
                    handleOutboxEvent(meetingId, event)
                }
            }.onFailure { e ->
                logger.warn(e) { "Companion outbox stream ended: meeting=$meetingId" }
            }
        }

        active[meetingId] = ActiveSession(resp.session_id, meetingId, streamJob)
        logger.info { "MeetingCompanionAssistant: started session=${resp.session_id} for meeting=$meetingId" }
        return resp.session_id
    }

    /** Push a freshly transcribed segment into the session inbox. No-op if assistant not active for this meeting. */
    suspend fun forwardSegment(meetingId: String, text: String) {
        val session = active[meetingId] ?: return
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        runCatching {
            companionClient.sendEvent(
                session.sessionId,
                OrchestratorCompanionClient.SessionEventRequest(
                    type = "meeting",
                    content = trimmed,
                    meta = emptyMap(),
                ),
            )
        }.onFailure { e ->
            logger.debug { "forwardSegment failed for meeting=$meetingId: ${e.message}" }
        }
    }

    /** Stop the assistant session. */
    suspend fun stop(meetingId: String) {
        val session = active.remove(meetingId) ?: return
        session.streamJob.cancel()
        runCatching { companionClient.stopSession(session.sessionId) }
            .onFailure { e -> logger.debug { "stopSession failed: ${e.message}" } }
        logger.info { "MeetingCompanionAssistant: stopped session=${session.sessionId} for meeting=$meetingId" }
    }

    private suspend fun handleOutboxEvent(meetingId: String, event: OrchestratorCompanionClient.OutboxEventDto) {
        val text = event.content.trim()
        if (text.isBlank()) return
        // Only surface suggestions and answers to the user. Notes are internal.
        val helperType = when (event.type) {
            "answer" -> HelperMessageType.SUGGESTION
            "suggestion" -> HelperMessageType.SUGGESTION
            else -> return
        }
        helperService.pushMessage(
            meetingId,
            HelperMessageDto(
                type = helperType,
                text = text,
                timestamp = Instant.now().toString(),
            ),
        )
    }

    private fun buildBrief(meetingId: String, meetingTitle: String?, userName: String?): String = buildString {
        appendLine("# Asistent v živém meetingu")
        appendLine()
        appendLine("## Role")
        appendLine("Jsi osobní asistent uživatele${userName?.let { " $it" } ?: ""} v živém meetingu. ")
        appendLine("Posluchej přepis (events type=meeting) a **proaktivně** dávej STRUČNÉ hinty.")
        appendLine()
        appendLine("## Pravidla výstupu")
        appendLine("- Každá odpověď MAX 2 věty, česky, do ucha přes sluchátka — NE listing, NE odrážky.")
        appendLine("- Piš JEN když je to užitečné. Tichý mód je default.")
        appendLine("- Použij `type=suggestion` když navrhuješ kontext / odpověď / varování.")
        appendLine("- Použij `type=answer` pouze když uživatel přímo položí otázku (event type=user).")
        appendLine("- Všechno ostatní ignoruj — žádné summary, žádné poznámky do outboxu.")
        appendLine()
        appendLine("## Kontext")
        appendLine("- Meeting ID: $meetingId")
        meetingTitle?.let { appendLine("- Téma: $it") }
        appendLine("- Začni tichý; čekej na obsah z events type=meeting a user.")
    }
}
