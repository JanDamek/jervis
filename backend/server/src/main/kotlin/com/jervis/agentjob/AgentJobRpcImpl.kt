package com.jervis.agentjob

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.agentjob.AgentJobListSnapshot
import com.jervis.dto.agentjob.AgentJobSnapshot
import com.jervis.dto.agentjob.AgentJobState
import com.jervis.dto.agentjob.AgentNarrativeEvent
import com.jervis.dto.events.JervisEvent
import com.jervis.rpc.NotificationRpcImpl
import com.jervis.service.agentjob.IAgentJobService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.exists

@Component
class AgentJobRpcImpl(
    private val agentJobRecordRepository: AgentJobRecordRepository,
    private val notificationRpc: NotificationRpcImpl,
    private val claudeStreamJsonlParser: ClaudeStreamJsonlParser,
) : IAgentJobService {
    private val logger = KotlinLogging.logger {}

    override fun subscribeAgentJobs(
        clientId: String?,
        projectId: String?,
        includeTerminalForHours: Int,
    ): Flow<AgentJobListSnapshot> = flow {
        val window = includeTerminalForHours.coerceIn(1, 24 * 7)
        // First emission — current snapshot.
        emit(buildSnapshot(clientId, projectId, window))

        // Bind to the global JervisEvent stream and re-build the snapshot on
        // every AgentJobStateChanged event — the broadcaster (Fáze G) fans out
        // to every registered client id, so we use a pseudo-client name unique
        // per subscription so the back-pressure buffer stays per-collector.
        val subscriberClientId = "agentjob-rpc-${java.util.UUID.randomUUID()}"
        val eventStream = notificationRpc.subscribeToEvents(subscriberClientId)
            .filter { evt ->
                if (evt !is JervisEvent.AgentJobStateChanged) false
                else when {
                    clientId != null && evt.clientId != clientId -> false
                    projectId != null && evt.projectId != projectId -> false
                    else -> true
                }
            }

        eventStream.collect {
            emit(buildSnapshot(clientId, projectId, window))
        }
    }

    private suspend fun buildSnapshot(
        clientId: String?,
        projectId: String?,
        windowHours: Int,
    ): AgentJobListSnapshot {
        val cutoff = Instant.now().minus(windowHours.toLong(), ChronoUnit.HOURS)

        val nonTerminal = agentJobRecordRepository
            .findByStateInOrderByCreatedAtAsc(
                listOf(
                    AgentJobState.QUEUED,
                    AgentJobState.RUNNING,
                    AgentJobState.WAITING_USER,
                ),
            )
            .toList()

        val terminal = agentJobRecordRepository
            .findByStateInAndCompletedAtAfterOrderByCompletedAtDesc(
                listOf(AgentJobState.DONE, AgentJobState.ERROR, AgentJobState.CANCELLED),
                cutoff,
            )
            .toList()

        val all = (nonTerminal + terminal).filter { record ->
            (clientId == null || record.clientId?.toString() == clientId) &&
                (projectId == null || record.projectId?.toString() == projectId)
        }

        val running = all.filter { it.state == AgentJobState.RUNNING }
            .sortedBy { it.startedAt ?: it.createdAt }
            .map { it.toSnapshot() }
        val queued = all.filter { it.state == AgentJobState.QUEUED }
            .sortedBy { it.createdAt }
            .map { it.toSnapshot() }
        val waitingUser = all.filter { it.state == AgentJobState.WAITING_USER }
            .sortedBy { it.createdAt }
            .map { it.toSnapshot() }
        val recent = all.filter { it.state.isTerminal() }
            .sortedByDescending { it.completedAt ?: it.createdAt }
            .map { it.toSnapshot() }

        return AgentJobListSnapshot(
            running = running,
            queued = queued,
            waitingUser = waitingUser,
            recent = recent,
            snapshotAt = Instant.now().toString(),
        )
    }

    override fun subscribeAgentNarrative(agentJobId: String): Flow<AgentNarrativeEvent> = flow {
        val id = try {
            com.jervis.common.types.AgentJobId(org.bson.types.ObjectId(agentJobId))
        } catch (e: Exception) {
            emit(
                AgentNarrativeEvent.NarrativeUnavailable(
                    timestamp = Instant.now().toString(),
                    reason = "invalid agentJobId: $agentJobId",
                ),
            )
            return@flow
        }
        val record = agentJobRecordRepository.getById(id) ?: run {
            emit(
                AgentNarrativeEvent.NarrativeUnavailable(
                    timestamp = Instant.now().toString(),
                    reason = "agent job not found",
                ),
            )
            return@flow
        }
        val streamFile = record.workspacePath?.let { Paths.get(it).resolve(".jervis/claude-stream.jsonl") }
        if (streamFile == null || !streamFile.exists()) {
            emit(
                AgentNarrativeEvent.NarrativeUnavailable(
                    timestamp = Instant.now().toString(),
                    reason = "narrative file missing — pre-Fáze-I dispatch or worktree cleaned",
                ),
            )
            return@flow
        }

        // Replay everything that's already on disk, then if the job is not
        // terminal poll the file for new lines on a 1 s cadence. The file
        // grows append-only; we track byte offset to avoid re-emitting old
        // events.
        var offset = 0L
        val initial = readNewLines(streamFile, offset)
        offset = initial.newOffset
        for (event in claudeStreamJsonlParser.parseAll(initial.text)) emit(event)

        if (record.state.isTerminal()) {
            // Snapshot done — terminal jobs don't grow further.
            return@flow
        }

        // Live tail. We stop when the record reaches terminal state on the
        // server side; check on each poll. No hard timeout (project rule).
        while (true) {
            kotlinx.coroutines.delay(1_000)
            val refreshed = agentJobRecordRepository.getById(id) ?: break
            val chunk = readNewLines(streamFile, offset)
            offset = chunk.newOffset
            if (chunk.text.isNotEmpty()) {
                for (event in claudeStreamJsonlParser.parseAll(chunk.text)) emit(event)
            }
            if (refreshed.state.isTerminal()) {
                // One last drain after the terminal flip — the entrypoint
                // may have flushed more lines between our previous read and
                // the K8s Watch event.
                val tail = readNewLines(streamFile, offset)
                offset = tail.newOffset
                for (event in claudeStreamJsonlParser.parseAll(tail.text)) emit(event)
                break
            }
        }
    }

    private suspend fun readNewLines(path: Path, fromOffset: Long): NewBytes = withContext(Dispatchers.IO) {
        val length = runCatching { Files.size(path) }.getOrDefault(0L)
        if (length <= fromOffset) return@withContext NewBytes("", fromOffset)
        try {
            java.io.RandomAccessFile(path.toFile(), "r").use { raf ->
                raf.seek(fromOffset)
                val buf = ByteArray((length - fromOffset).toInt().coerceAtMost(2 * 1024 * 1024))
                val n = raf.read(buf)
                if (n <= 0) NewBytes("", fromOffset) else NewBytes(String(buf, 0, n, Charsets.UTF_8), fromOffset + n)
            }
        } catch (e: Exception) {
            logger.warn { "readNewLines | $path failed: ${e.message}" }
            NewBytes("", fromOffset)
        }
    }

    private data class NewBytes(val text: String, val newOffset: Long)

    private fun AgentJobRecord.toSnapshot(): AgentJobSnapshot = AgentJobSnapshot(
        id = id.toString(),
        flavor = flavor.name,
        state = state.name,
        title = title,
        clientId = clientId?.toString(),
        projectId = projectId?.toString(),
        resourceId = resourceId,
        gitBranch = gitBranch,
        gitCommitSha = gitCommitSha,
        resultSummary = resultSummary,
        errorMessage = errorMessage,
        artifacts = artifacts,
        createdAt = createdAt.toString(),
        startedAt = startedAt?.toString(),
        completedAt = completedAt?.toString(),
        dispatchedBy = dispatchedBy,
        workspacePath = workspacePath,
    )
}
