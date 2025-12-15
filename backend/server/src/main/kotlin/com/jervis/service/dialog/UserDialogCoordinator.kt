package com.jervis.service.dialog

import com.jervis.service.notification.NotificationsPublisher
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class UserDialogCoordinator(
    private val notificationsPublisher: NotificationsPublisher,
) {
    private val logger = KotlinLogging.logger {}

    private val mutex = Mutex()

    private var active: ActiveDialog? = null

    private companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 15 * 60 * 1000 // 15 minutes
    }

    data class DialogResult(
        val accepted: Boolean,
        val answer: String?,
        val closedByUser: Boolean,
    )

    private data class ActiveDialog(
        val dialogId: String,
        val correlationId: String,
        val clientId: ClientId,
        val projectId: ProjectId?,
        val result: CompletableDeferred<DialogResult>,
    )

    suspend fun requestDialog(
        clientId: ClientId,
        projectId: ProjectId?,
        correlationId: String,
        question: String,
        proposedAnswer: String?,
    ): DialogResult {
        // Ensure only a single active dialog in the whole system
        val activeDialog =
            mutex.withLock {
                if (active != null) {
                    logger.info { "UserDialog already active, waiting for it to complete..." }
                }
                while (active != null) {
                    // Busy-wait avoidance: release lock and wait for completion
                    val toAwait = active!!.result
                    mutex.unlock()
                    runCatching { toAwait.await() }
                    mutex.lock()
                }

                val newActive =
                    ActiveDialog(
                        dialogId = UUID.randomUUID().toString(),
                        correlationId = correlationId,
                        clientId = clientId,
                        projectId = projectId,
                        result = CompletableDeferred(),
                    )
                active = newActive
                newActive
            }

        // Broadcast request to all connected UI devices
        notificationsPublisher.publishUserDialogRequest(
            dialogId = activeDialog.dialogId,
            correlationId = correlationId,
            clientId = clientId,
            projectId = projectId,
            question = question,
            proposedAnswer = proposedAnswer,
            timestamp = Instant.now().toString(),
        )

        // Await response or close
        val result =
            try {
                val awaited = withTimeoutOrNull(DEFAULT_TIMEOUT_MS) { activeDialog.result.await() }
                awaited ?: DialogResult(accepted = false, answer = null, closedByUser = true)
            } catch (e: Exception) {
                logger.warn(e) { "UserDialog awaiting failed" }
                DialogResult(accepted = false, answer = null, closedByUser = true)
            }

        // Ensure a close broadcast to all UIs
        notificationsPublisher.publishUserDialogClose(
            dialogId = activeDialog.dialogId,
            correlationId = correlationId,
            reason = if (result.accepted) "ANSWERED" else "CLOSED",
            timestamp = Instant.now().toString(),
        )

        // Clear active
        mutex.withLock { active = null }
        return result
    }

    suspend fun handleClientResponse(
        dialogId: String,
        correlationId: String,
        answer: String,
        accepted: Boolean,
    ) {
        val toComplete = mutex.withLock { active?.takeIf { it.dialogId == dialogId } }
        if (toComplete == null) {
            logger.warn { "No active dialog to respond (dialogId=$dialogId)" }
            return
        }
        if (toComplete.correlationId != correlationId) {
            logger.warn { "CorrelationId mismatch for dialog $dialogId" }
        }
        toComplete.result.complete(DialogResult(accepted = accepted, answer = answer, closedByUser = false))
    }

    suspend fun handleClientClose(
        dialogId: String,
        correlationId: String,
    ) {
        val toComplete = mutex.withLock { active?.takeIf { it.dialogId == dialogId } }
        if (toComplete == null) {
            logger.warn { "No active dialog to close (dialogId=$dialogId)" }
            return
        }
        if (toComplete.correlationId != correlationId) {
            logger.warn { "CorrelationId mismatch for dialog $dialogId on close" }
        }
        toComplete.result.complete(DialogResult(accepted = false, answer = null, closedByUser = true))
    }
}
