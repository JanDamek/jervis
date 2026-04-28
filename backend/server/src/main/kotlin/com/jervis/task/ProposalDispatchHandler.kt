package com.jervis.task

import com.jervis.agentjob.AgentJobDispatcher
import com.jervis.agentjob.AgentJobRecord
import com.jervis.bugtracker.BugTrackerService
import com.jervis.bugtracker.CreateBugTrackerIssueRequest
import com.jervis.connection.ConnectionDocument
import com.jervis.connection.ConnectionRepository
import com.jervis.dto.agentjob.AgentJobFlavor
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.dto.connection.ProviderEnum
import com.jervis.dto.task.TaskStateEnum
import com.jervis.infrastructure.grpc.O365BrowserPoolGrpcClient
import com.jervis.meeting.MeetingAttendApprovalService
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * PR-Q4 — Routes APPROVED proposed tasks to the correct execution
 * handler based on [TaskDocument.proposalTaskType]. Called from
 * [BackgroundEngine] pickup once a task transitions to ``state=QUEUED``
 * with ``proposalStage=APPROVED`` (i.e. ``task_approve`` has fired).
 *
 * Each handler is responsible for the side-effect (mail send / Teams
 * post / calendar response / bugtracker insert / coding agent dispatch
 * / meeting attend kick-off) AND for transitioning the task to its
 * terminal state (``DONE`` / ``ERROR`` / ``CODING`` / ``USER_TASK``).
 *
 * Important invariants:
 *   - CODING flavor MUST be dispatched via [AgentJobDispatcher.dispatch]
 *     with ``dispatchTriggeredBy="ui_approval"``. PR2b validates the
 *     trigger; any blank / unknown value throws [IllegalArgumentException].
 *   - Mail / Teams / Calendar handlers fan instructions into the O365
 *     browser pool pod via [O365BrowserPoolGrpcClient.pushInstruction].
 *     The Microsoft Graph API client was retired in commit ``6c768af9e``
 *     — all O365 side-effects now flow through the browser-pod LangGraph
 *     agent.
 *   - All user-facing strings (errorMessage / pendingUserQuestion) use
 *     Czech per project conventions.
 */
@Service
class ProposalDispatchHandler(
    private val taskService: TaskService,
    private val taskRepository: TaskRepository,
    private val agentJobDispatcher: AgentJobDispatcher,
    private val bugTrackerService: BugTrackerService,
    private val meetingAttendApprovalService: MeetingAttendApprovalService,
    private val o365BrowserPoolGrpc: O365BrowserPoolGrpcClient,
    private val connectionRepository: ConnectionRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Dispatch an APPROVED proposed task. Caller (BackgroundEngine) is
     * expected to claim the task before invoking — the handler will
     * transition state on its own (DONE / ERROR / CODING / USER_TASK).
     *
     * @return ``true`` if dispatch succeeded (task DONE or progressed
     *         to a known async state — CODING / USER_TASK). ``false``
     *         on failure; the caller should follow normal retry/error
     *         behaviour. Non-recoverable failures escalate to
     *         ``state=USER_TASK`` so the user can intervene.
     */
    suspend fun dispatch(task: TaskDocument): Boolean {
        val type = task.proposalTaskType
        if (type == null) {
            logger.error {
                "PROPOSAL_DISPATCH_INVALID: task=${task.id} has proposalStage=APPROVED but proposalTaskType=null — manual review"
            }
            taskService.transitionToUserTask(
                task = task,
                pendingQuestion = "Návrh schválen, ale chybí typ akce (proposalTaskType=null). Vyřešte ručně.",
                questionContext = "BackgroundEngine pickup: proposalStage=APPROVED, proposalTaskType=null",
            )
            return false
        }
        return when (type) {
            ProposalTaskType.CODING -> dispatchCoding(task)
            ProposalTaskType.MAIL_REPLY -> dispatchMailReply(task)
            ProposalTaskType.TEAMS_REPLY -> dispatchTeamsReply(task)
            ProposalTaskType.CALENDAR_RESPONSE -> dispatchCalendarResponse(task)
            ProposalTaskType.BUGTRACKER_ENTRY -> dispatchBugTrackerEntry(task)
            ProposalTaskType.MEETING_ATTEND -> dispatchMeetingAttend(task)
            ProposalTaskType.OTHER -> {
                logger.warn {
                    "PROPOSAL_DISPATCH_OTHER: task=${task.id} proposalTaskType=OTHER — escalating to USER_TASK"
                }
                taskService.transitionToUserTask(
                    task = task,
                    pendingQuestion = "Schválený návrh typu OTHER vyžaduje ruční vyřešení.",
                    questionContext = "Automatické zpracování není pro typ OTHER definováno.",
                )
                false
            }
        }
    }

    // ── CODING ─────────────────────────────────────────────────────────

    private suspend fun dispatchCoding(task: TaskDocument): Boolean {
        val projectId = task.projectId
        if (projectId == null) {
            logger.error { "PROPOSAL_DISPATCH_CODING_NO_PROJECT: task=${task.id}" }
            return failError(task, "CODING task vyžaduje projectId — chybí")
        }
        // Resource resolution: caller (qualifier) does NOT currently
        // populate a per-task resourceId on the proposal. Pick the first
        // git resource of the project as a sensible default. If none
        // exists we cannot dispatch — escalate.
        val branchName = "task/${task.id}"
        return try {
            val record: AgentJobRecord = agentJobDispatcher.dispatch(
                flavor = AgentJobFlavor.CODING,
                title = task.taskName,
                description = task.content,
                clientId = task.clientId,
                projectId = projectId,
                resourceId = null, // dispatcher resolves per-project default; if missing, dispatcher fails record
                dispatchedBy = "background-engine:${task.id}",
                branchName = branchName,
                dispatchTriggeredBy = "ui_approval",
            )
            // Move task to CODING and persist agent job linkage. Use raw
            // repository save because TaskService has no helper for the
            // CODING transition (state + agentJobName + agentJobStartedAt).
            val updated = task.copy(
                state = TaskStateEnum.CODING,
                agentJobName = record.kubernetesJobName ?: AgentJobDispatcher.codingJobName(record.id),
                agentJobState = record.state.name,
                agentJobStartedAt = record.startedAt ?: java.time.Instant.now(),
                agentJobAgentType = "claude",
            )
            taskRepository.save(updated)
            logger.info {
                "PROPOSAL_DISPATCH_CODING_OK: task=${task.id} agentJobId=${record.id} branch=$branchName"
            }
            true
        } catch (e: Exception) {
            logger.error(e) { "PROPOSAL_DISPATCH_CODING_FAIL: task=${task.id}" }
            failError(task, "CODING dispatch selhal: ${e.message ?: e::class.simpleName}")
        }
    }

    // ── MAIL_REPLY ─────────────────────────────────────────────────────

    private suspend fun dispatchMailReply(task: TaskDocument): Boolean {
        val parsed = parseMailReplyDraft(task.content)
        if (parsed == null) {
            logger.error { "PROPOSAL_DISPATCH_MAIL_PARSE_FAIL: task=${task.id}" }
            return failError(task, "MAIL_REPLY draft se nepodařilo přečíst (chybí Reply to / Re:)")
        }
        val connection = findActiveO365Connection(task) ?: run {
            return failError(task, "MAIL_REPLY: žádné aktivní O365 připojení pro klienta ${task.clientId}")
        }
        val o365ClientId = connection.o365ClientId ?: connection.id.toString()
        val instruction = buildString {
            append("INSTRUCTION: send_mail. ")
            append("to=${parsed.recipients.joinToString(",")}. ")
            append("subject=${parsed.subject}. ")
            append("Compose: open mail composer (Outlook web), set To and Subject, ")
            append("paste the body below verbatim, then click Send. ")
            append("Body:\n${parsed.body}")
        }
        return pushAndFinish(
            task = task,
            connection = connection,
            o365ClientIdForLog = o365ClientId,
            instruction = instruction,
            successSummary = "Mail dispatched to ${parsed.recipients.joinToString(", ")}",
            failureLabel = "MAIL_REPLY",
        )
    }

    // ── TEAMS_REPLY ────────────────────────────────────────────────────

    private suspend fun dispatchTeamsReply(task: TaskDocument): Boolean {
        val parsed = parseTeamsReplyDraft(task.content)
        if (parsed == null) {
            logger.error { "PROPOSAL_DISPATCH_TEAMS_PARSE_FAIL: task=${task.id}" }
            return failError(task, "TEAMS_REPLY draft se nepodařilo přečíst (chybí 'Reply on Teams chat from <sender>')")
        }
        val connection = findActiveO365Connection(task) ?: run {
            return failError(task, "TEAMS_REPLY: žádné aktivní O365 připojení pro klienta ${task.clientId}")
        }
        val o365ClientId = connection.o365ClientId ?: connection.id.toString()
        val instruction = buildString {
            append("INSTRUCTION: send_teams_chat_message. ")
            append("chat_target='${parsed.chatTarget}'. ")
            append("Compose: open Teams chat with target person/chat by name, ")
            append("paste the body below into the message composer, click Send. ")
            append("Body:\n${parsed.body}")
        }
        return pushAndFinish(
            task = task,
            connection = connection,
            o365ClientIdForLog = o365ClientId,
            instruction = instruction,
            successSummary = "Teams reply dispatched to ${parsed.chatTarget}",
            failureLabel = "TEAMS_REPLY",
        )
    }

    // ── CALENDAR_RESPONSE ──────────────────────────────────────────────

    private suspend fun dispatchCalendarResponse(task: TaskDocument): Boolean {
        val connection = findActiveO365Connection(task) ?: run {
            return failError(task, "CALENDAR_RESPONSE: žádné aktivní O365 připojení pro klienta ${task.clientId}")
        }
        val o365ClientId = connection.o365ClientId ?: connection.id.toString()
        // Heuristic: pull the event subject from task.taskName ("Calendar
        // response: <subject>") and the recommended response from the
        // last ":" line of the body (Accept / Decline / Tentative).
        val response = parseCalendarResponse(task.content)
        val subjectHint = task.taskName.removePrefix("Calendar response:").trim().ifBlank { task.taskName }
        val instruction = buildString {
            append("INSTRUCTION: respond_to_calendar_event. ")
            append("subject_hint='$subjectHint'. ")
            append("response='$response'. ")
            append("Compose: open Outlook calendar, find the event matching the subject hint, ")
            append("click Accept / Decline / Tentative as instructed, then send the response. ")
            append("Body:\n${task.content}")
        }
        return pushAndFinish(
            task = task,
            connection = connection,
            o365ClientIdForLog = o365ClientId,
            instruction = instruction,
            successSummary = "Calendar response '$response' dispatched for event '$subjectHint'",
            failureLabel = "CALENDAR_RESPONSE",
        )
    }

    // ── BUGTRACKER_ENTRY ───────────────────────────────────────────────

    private suspend fun dispatchBugTrackerEntry(task: TaskDocument): Boolean {
        val parsed = parseBugTrackerEntry(task.content)
        if (parsed.projectKey.isBlank()) {
            // No explicit project key — escalate; we don't guess which
            // tracker project to file against.
            taskService.transitionToUserTask(
                task = task,
                pendingQuestion = "Doplňte 'Project: <KEY>' do popisu bug-tracker úlohy a znovu schvalte.",
                questionContext = "BUGTRACKER_ENTRY potřebuje projektový klíč (např. PROJ).",
            )
            logger.warn { "PROPOSAL_DISPATCH_BUGTRACKER_NO_KEY: task=${task.id}" }
            return false
        }
        return try {
            val issue = bugTrackerService.createIssue(
                clientId = task.clientId,
                request = CreateBugTrackerIssueRequest(
                    projectKey = parsed.projectKey,
                    summary = parsed.summary.ifBlank { task.taskName },
                    description = parsed.description,
                    issueType = parsed.issueType,
                    priority = parsed.priority,
                    assignee = parsed.assignee,
                    labels = parsed.labels,
                ),
            )
            taskService.updateState(task, TaskStateEnum.DONE)
            logger.info {
                "PROPOSAL_DISPATCH_BUGTRACKER_OK: task=${task.id} created issue=${issue.key}"
            }
            true
        } catch (e: Exception) {
            logger.error(e) { "PROPOSAL_DISPATCH_BUGTRACKER_FAIL: task=${task.id}" }
            failError(task, "BUGTRACKER_ENTRY selhal: ${e.message ?: e::class.simpleName}")
        }
    }

    // ── MEETING_ATTEND ─────────────────────────────────────────────────

    private suspend fun dispatchMeetingAttend(task: TaskDocument): Boolean {
        // The meeting-attend approval flow runs in
        // [MeetingAttendApprovalService] — we delegate to its public
        // resolver as if the user had tapped Approve. The service
        // handles queue update, push cancellation, recording dispatch
        // wiring and chat bubble.
        return try {
            meetingAttendApprovalService.handleApprovalResponse(
                task = task,
                approved = true,
                reason = "ProposalDispatchHandler: APPROVED via UI approval",
            )
            logger.info { "PROPOSAL_DISPATCH_MEETING_OK: task=${task.id}" }
            true
        } catch (e: Exception) {
            logger.error(e) { "PROPOSAL_DISPATCH_MEETING_FAIL: task=${task.id}" }
            failError(task, "MEETING_ATTEND dispatch selhal: ${e.message ?: e::class.simpleName}")
        }
    }

    // ── shared helpers ─────────────────────────────────────────────────

    /**
     * Find the most recently active O365 (MICROSOFT_TEAMS) connection
     * belonging to the task's client. Used to address the right
     * browser-pool pod for mail / Teams / calendar instructions.
     *
     * Returns null when no connection exists or none is in a usable
     * state — the caller must escalate (the user has to log in via the
     * browser-pod noVNC before automated actions can fire).
     */
    private suspend fun findActiveO365Connection(task: TaskDocument): ConnectionDocument? {
        // ConnectionDocument has no per-client linkage in the current
        // schema; the browser-pool routes by `o365ClientId` (one pod per
        // logged-in user). Pick the first VALID MICROSOFT_TEAMS
        // connection — there is typically only one. PAUSED connections
        // are intentionally skipped.
        val candidates = connectionRepository
            .findAllByState(ConnectionStateEnum.VALID)
            .toList()
        val match = candidates.firstOrNull { it.provider == ProviderEnum.MICROSOFT_TEAMS }
        if (match == null) {
            logger.warn { "PROPOSAL_DISPATCH_NO_O365_CONN: task=${task.id} client=${task.clientId}" }
        }
        return match
    }

    private suspend fun pushAndFinish(
        task: TaskDocument,
        connection: ConnectionDocument,
        o365ClientIdForLog: String,
        instruction: String,
        successSummary: String,
        failureLabel: String,
    ): Boolean {
        return try {
            val resp = o365BrowserPoolGrpc.pushInstruction(
                connectionId = connection.id,
                clientId = o365ClientIdForLog,
                instruction = instruction,
            )
            if (resp.status == "queued") {
                taskService.updateState(task, TaskStateEnum.DONE)
                logger.info {
                    "PROPOSAL_DISPATCH_${failureLabel}_OK: task=${task.id} conn=${connection.id} → $successSummary"
                }
                true
            } else {
                logger.warn {
                    "PROPOSAL_DISPATCH_${failureLabel}_REJECTED: task=${task.id} status=${resp.status} error=${resp.error}"
                }
                failError(
                    task,
                    "$failureLabel pod returned status=${resp.status} error=${resp.error.take(200)}",
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "PROPOSAL_DISPATCH_${failureLabel}_FAIL: task=${task.id}" }
            failError(task, "$failureLabel push selhal: ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * Mark the task as ERROR with a Czech operator-facing message.
     * Always returns ``false`` so callers can ``return failError(...)``
     * directly from a handler branch.
     */
    private suspend fun failError(task: TaskDocument, reason: String): Boolean {
        taskService.markAsError(task, reason)
        return false
    }

    // ── parsers ────────────────────────────────────────────────────────

    private data class MailReplyDraft(
        val recipients: List<String>,
        val subject: String,
        val body: String,
    )

    /**
     * Parse the conventional qualifier mail-reply draft format:
     * ```
     * Re: <subject>
     *
     * Reply to: <recipient_email>[, <recipient_email>...]
     *
     * --- Original message ---
     * <original body>
     *
     * --- Draft reply (refine before send) ---
     * <reply text>
     * ```
     * Returns null if either ``Re:`` / ``Reply to:`` / draft separator
     * is missing.
     */
    private fun parseMailReplyDraft(content: String): MailReplyDraft? {
        val lines = content.lines()
        val subjectLine = lines.firstOrNull { it.startsWith("Re:", ignoreCase = true) } ?: return null
        val subject = subjectLine.substringAfter(":").trim().ifBlank { return null }
        val replyToLine = lines.firstOrNull { it.startsWith("Reply to:", ignoreCase = true) } ?: return null
        val recipients = replyToLine.substringAfter(":")
            .split(",", ";")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("@") }
        if (recipients.isEmpty()) return null
        val draftMarker = "--- Draft reply (refine before send) ---"
        val draftIdx = content.indexOf(draftMarker).takeIf { it >= 0 } ?: return null
        val body = content.substring(draftIdx + draftMarker.length).trim()
        if (body.isBlank()) return null
        return MailReplyDraft(recipients = recipients, subject = subject, body = body)
    }

    private data class TeamsReplyDraft(
        val chatTarget: String,
        val body: String,
    )

    /**
     * Parse the conventional qualifier Teams-reply draft format:
     * ```
     * Reply on Teams chat from <sender>.
     *
     * --- Original message ---
     * <body>
     *
     * --- Draft reply (refine before send) ---
     * <reply text>
     * ```
     * Returns null when sender or draft section is missing. The
     * ``chatTarget`` is the ``<sender>`` literal — the o365 browser-pod
     * agent then resolves the chat by display name from the sidebar.
     */
    private fun parseTeamsReplyDraft(content: String): TeamsReplyDraft? {
        val first = content.lines().firstOrNull { it.startsWith("Reply on Teams chat from", ignoreCase = true) }
            ?: return null
        val sender = first
            .substringAfter("from", missingDelimiterValue = "")
            .trim()
            .removeSuffix(".")
            .trim()
            .ifBlank { return null }
        val draftMarker = "--- Draft reply (refine before send) ---"
        val draftIdx = content.indexOf(draftMarker).takeIf { it >= 0 } ?: return null
        val body = content.substring(draftIdx + draftMarker.length).trim()
        if (body.isBlank()) return null
        return TeamsReplyDraft(chatTarget = sender, body = body)
    }

    /**
     * Crude heuristic — looks for the words accept / decline / tentative
     * (Czech: přijmout / odmítnout / nezávazně) anywhere in the body and
     * returns the canonical English token. Defaults to ``tentative``
     * (least destructive) when nothing matches; the o365 pod agent will
     * surface a USER_TASK if the LLM cannot decide.
     */
    private fun parseCalendarResponse(content: String): String {
        val lc = content.lowercase()
        return when {
            "accept" in lc || "přijmout" in lc || "potvrdit" in lc -> "accept"
            "decline" in lc || "odmítnout" in lc || "zamítnout" in lc -> "decline"
            else -> "tentative"
        }
    }

    private data class BugTrackerDraft(
        val projectKey: String,
        val summary: String,
        val description: String,
        val issueType: String,
        val priority: String?,
        val assignee: String?,
        val labels: List<String>,
    )

    /**
     * Parse a free-form bug-tracker draft. Required tokens (any line
     * order, case-insensitive label):
     *   - ``Project: <KEY>``  — projectKey, e.g. ``PROJ``
     *   - ``Summary: <text>`` — issue title
     *   - ``Type: <Bug|Task|Story>`` — defaults to ``Task``
     *   - ``Priority: <name>`` — optional
     *   - ``Assignee: <user>`` — optional
     *   - ``Labels: a, b, c`` — optional, comma-separated
     * Description is the remainder after these labelled lines (or the
     * whole content if no labels are present).
     */
    private fun parseBugTrackerEntry(content: String): BugTrackerDraft {
        var project = ""
        var summary = ""
        var type = "Task"
        var priority: String? = null
        var assignee: String? = null
        val labels = mutableListOf<String>()
        val descriptionBuilder = StringBuilder()
        for (raw in content.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("Project:", ignoreCase = true) -> project = line.substringAfter(":").trim()
                line.startsWith("Summary:", ignoreCase = true) -> summary = line.substringAfter(":").trim()
                line.startsWith("Type:", ignoreCase = true) -> type = line.substringAfter(":").trim().ifBlank { "Task" }
                line.startsWith("Priority:", ignoreCase = true) -> priority = line.substringAfter(":").trim().ifBlank { null }
                line.startsWith("Assignee:", ignoreCase = true) -> assignee = line.substringAfter(":").trim().ifBlank { null }
                line.startsWith("Labels:", ignoreCase = true) -> {
                    line.substringAfter(":")
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { labels.add(it) }
                }
                else -> {
                    if (descriptionBuilder.isNotEmpty()) descriptionBuilder.append('\n')
                    descriptionBuilder.append(raw)
                }
            }
        }
        return BugTrackerDraft(
            projectKey = project,
            summary = summary,
            description = descriptionBuilder.toString().trim(),
            issueType = type,
            priority = priority,
            assignee = assignee,
            labels = labels.toList(),
        )
    }
}
