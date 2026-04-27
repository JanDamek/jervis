package com.jervis.connection

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.SourceUrn
import com.jervis.common.types.TaskId
import com.jervis.dto.task.TaskStateEnum
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.infrastructure.notification.ApnsPushService
import com.jervis.infrastructure.notification.FcmPushService
import com.jervis.preferences.DeviceTokenRepository
import com.jervis.rpc.NotificationRpcImpl
import com.jervis.task.TaskDocument
import com.jervis.task.TaskRepository
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Global Login Consent Semaphore — one human user, one MFA challenge at a
 * time across ALL connections of ALL clients. See `LoginConsentDocument`
 * KDoc for the full lifecycle.
 *
 * Concurrency model: a single coroutine `Mutex` serializes every state
 * mutation (acquire / respond / release / advance). The MongoDB document
 * is rewritten on every change so a server restart inherits the queue.
 *
 * Long-poll wakeup: callers of `wait` register a `CompletableDeferred`
 * keyed by `requestId`; state changes complete it, the caller returns
 * the new state. A 60 s timeout returns "queued" / "deferred" so the pod
 * re-issues the wait.
 *
 * Background tick (10 s): advances the queue when `currentHolder` is
 * null OR expired, and force-releases stale ACTIVE_LOGIN holds.
 */
@Service
class LoginConsentService(
    private val repository: LoginConsentRepository,
    private val fcmPushService: FcmPushService,
    private val apnsPushService: ApnsPushService,
    private val taskRepository: TaskRepository,
    private val notificationRpc: NotificationRpcImpl,
    private val connectionRepository: ConnectionRepository,
    private val deviceTokenRepository: DeviceTokenRepository? = null,
) {
    private val logger = KotlinLogging.logger {}
    private val mutex = Mutex()
    private val waiters: MutableMap<String, CompletableDeferred<Unit>> = ConcurrentHashMap()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tickJob: Job? = null

    private val awaitingConsentTimeout = Duration.ofMinutes(10)
    private val activeLoginTimeout = Duration.ofMinutes(5)

    @PostConstruct
    fun onStart() {
        runBlocking {
            ensureDocumentExists()
        }
        tickJob = scope.launch {
            while (true) {
                try {
                    tick()
                } catch (e: Exception) {
                    logger.warn(e) { "LoginConsent tick failed" }
                }
                delay(10_000)
            }
        }
        logger.info { "LoginConsentService started" }
    }

    @PreDestroy
    fun onStop() {
        runBlocking {
            tickJob?.cancelAndJoin()
        }
    }

    // ── Public API ──────────────────────────────────────────────────────

    data class AcquireResult(
        val requestId: String,
        val status: String, // "granted" | "queued"
        val position: Int,
        val token: String,
    )

    suspend fun acquire(connectionId: String, label: String, reason: String): AcquireResult =
        mutex.withLock {
            val state = load()
            val requestId = UUID.randomUUID().toString()
            val now = Instant.now()
            // If no holder and queue empty → promote immediately.
            if (state.currentHolder == null && state.queue.isEmpty()) {
                val token = UUID.randomUUID().toString()
                val holder = LoginConsentDocument.Holder(
                    requestId = requestId,
                    connectionId = connectionId,
                    label = label,
                    reason = reason,
                    phase = LoginConsentDocument.Phase.AWAITING_CONSENT,
                    token = token,
                    acquiredAt = now,
                    expiresAt = now.plus(awaitingConsentTimeout),
                )
                save(state.copy(currentHolder = holder, updatedAt = now))
                sendConsentPush(holder)
                logger.info { "LoginConsent: promoted ${label} to AWAITING_CONSENT (req=$requestId)" }
                AcquireResult(requestId, "queued", 0, "")
            } else {
                // Enqueue.
                val entry = LoginConsentDocument.QueuedEntry(
                    requestId = requestId,
                    connectionId = connectionId,
                    label = label,
                    reason = reason,
                    queuedAt = now,
                )
                val queue = state.queue + entry
                save(state.copy(queue = queue, updatedAt = now))
                logger.info { "LoginConsent: enqueued ${label} at position ${queue.size} (req=$requestId)" }
                AcquireResult(requestId, "queued", queue.size, "")
            }
        }

    data class WaitResult(
        val status: String, // "queued" | "deferred" | "granted" | "declined" | "expired"
        val position: Int,
        val deferredUntil: Instant?,
        val token: String,
        val declineReason: String,
    )

    /**
     * Long-poll: returns immediately if the entry is in a terminal state
     * (granted / declined / expired), otherwise suspends up to 60 s for
     * a state change.
     */
    suspend fun await(requestId: String): WaitResult {
        // First, snapshot current state.
        val snapshot = mutex.withLock { load() }
        val (terminal, result) = evaluate(snapshot, requestId)
        if (terminal) return result

        // Register waiter and wait.
        val deferred = CompletableDeferred<Unit>()
        waiters[requestId] = deferred
        try {
            withTimeoutOrNull(60_000) { deferred.await() }
        } finally {
            waiters.remove(requestId)
        }

        // Re-evaluate after wakeup or timeout.
        val state2 = mutex.withLock { load() }
        return evaluate(state2, requestId).second
    }

    /**
     * User pressed an action button on the consent push notification.
     */
    suspend fun respond(requestId: String, action: String) {
        mutex.withLock {
            val state = load()
            val holder = state.currentHolder
            if (holder == null || holder.requestId != requestId) {
                logger.warn { "LoginConsent.respond: requestId=$requestId is not currentHolder" }
                return@withLock
            }
            val now = Instant.now()
            when (action) {
                "now" -> {
                    val nextHolder = holder.copy(
                        phase = LoginConsentDocument.Phase.ACTIVE_LOGIN,
                        acquiredAt = now,
                        expiresAt = now.plus(activeLoginTimeout),
                        lastHeartbeatAt = now,
                    )
                    save(state.copy(currentHolder = nextHolder, updatedAt = now))
                    logger.info { "LoginConsent: GRANTED ${holder.label} (req=$requestId)" }
                    wake(requestId)
                }
                "defer_15", "defer_60" -> {
                    val deltaMin = if (action == "defer_15") 15L else 60L
                    val deferredEntry = LoginConsentDocument.QueuedEntry(
                        requestId = holder.requestId,
                        connectionId = holder.connectionId,
                        label = holder.label,
                        reason = holder.reason,
                        queuedAt = now,
                        availableAt = now.plus(Duration.ofMinutes(deltaMin)),
                        deferCount = holder.deferCount + 1,
                    )
                    save(state.copy(currentHolder = null, queue = state.queue + deferredEntry, updatedAt = now))
                    logger.info { "LoginConsent: DEFERRED ${holder.label} by $deltaMin min (req=$requestId, defer#${deferredEntry.deferCount})" }
                    // Drop the current chat bubble — a new one will appear when the
                    // entry is promoted again after `availableAt` expires.
                    resolveConsentTasks(requestId)
                    wake(requestId)
                    promoteIfPossible(now)
                }
                "cancel" -> {
                    save(state.copy(currentHolder = null, updatedAt = now))
                    logger.info { "LoginConsent: CANCELLED ${holder.label} (req=$requestId)" }
                    resolveConsentTasks(requestId)
                    wake(requestId)
                    promoteIfPossible(now)
                }
                else -> logger.warn { "LoginConsent.respond: unknown action=$action" }
            }
        }
    }

    /**
     * Pod reports the login flow finished (success/fail/expired/cancelled).
     */
    suspend fun release(requestId: String, token: String, outcome: String): String =
        mutex.withLock {
            val state = load()
            val holder = state.currentHolder
            if (holder == null || holder.requestId != requestId) {
                return@withLock "unknown_request"
            }
            if (holder.token != token) {
                return@withLock "token_mismatch"
            }
            save(state.copy(currentHolder = null, updatedAt = Instant.now()))
            logger.info { "LoginConsent: RELEASED ${holder.label} outcome=$outcome (req=$requestId)" }
            resolveConsentTasks(requestId)
            promoteIfPossible(Instant.now())
            "ok"
        }

    // ── Internal ────────────────────────────────────────────────────────

    private suspend fun tick() {
        mutex.withLock {
            val state = load()
            val now = Instant.now()
            val holder = state.currentHolder
            if (holder != null && now.isAfter(holder.expiresAt)) {
                logger.warn { "LoginConsent: holder ${holder.label} EXPIRED — force release" }
                save(state.copy(currentHolder = null, updatedAt = now))
                resolveConsentTasks(holder.requestId)
                wake(holder.requestId)
            }
            promoteIfPossible(Instant.now())
        }
    }

    private suspend fun promoteIfPossible(now: Instant) {
        val state = load()
        if (state.currentHolder != null) return
        val available = state.queue
            .filter { !it.availableAt.isAfter(now) }
            .minByOrNull { it.queuedAt }
            ?: return
        val token = UUID.randomUUID().toString()
        val holder = LoginConsentDocument.Holder(
            requestId = available.requestId,
            connectionId = available.connectionId,
            label = available.label,
            reason = available.reason,
            phase = LoginConsentDocument.Phase.AWAITING_CONSENT,
            token = token,
            acquiredAt = now,
            expiresAt = now.plus(awaitingConsentTimeout),
            deferCount = available.deferCount,
        )
        val newQueue = state.queue.filterNot { it.requestId == available.requestId }
        save(state.copy(currentHolder = holder, queue = newQueue, updatedAt = now))
        sendConsentPush(holder)
        logger.info { "LoginConsent: promoted ${available.label} from queue (req=${available.requestId}, defer#${available.deferCount})" }
    }

    private fun evaluate(state: LoginConsentDocument, requestId: String): Pair<Boolean, WaitResult> {
        val holder = state.currentHolder
        if (holder?.requestId == requestId) {
            return when (holder.phase) {
                LoginConsentDocument.Phase.AWAITING_CONSENT ->
                    false to WaitResult("queued", 0, null, "", "")
                LoginConsentDocument.Phase.ACTIVE_LOGIN ->
                    true to WaitResult("granted", 0, null, holder.token, "")
            }
        }
        val queueIdx = state.queue.indexOfFirst { it.requestId == requestId }
        if (queueIdx >= 0) {
            val entry = state.queue[queueIdx]
            val deferred = entry.availableAt.isAfter(Instant.now())
            return false to WaitResult(
                if (deferred) "deferred" else "queued",
                queueIdx + 1,
                if (deferred) entry.availableAt else null,
                "",
                "",
            )
        }
        // Not in current holder, not in queue — either declined/cancelled or unknown.
        return true to WaitResult("declined", 0, null, "", "user_cancelled_or_expired")
    }

    private fun wake(requestId: String) {
        waiters.remove(requestId)?.complete(Unit)
    }

    private suspend fun load(): LoginConsentDocument =
        repository.findById(LoginConsentDocument.GLOBAL_ID)
            ?: LoginConsentDocument().also { repository.save(it) }

    private suspend fun ensureDocumentExists() {
        if (repository.findById(LoginConsentDocument.GLOBAL_ID) == null) {
            repository.save(LoginConsentDocument())
            logger.info { "LoginConsent: initialized GLOBAL document" }
        }
    }

    private suspend fun save(doc: LoginConsentDocument) {
        repository.save(doc)
    }

    /**
     * Surface the consent request on user-facing channels:
     *   1. TaskDocument with `interruptAction = "login_consent"` so the
     *      chat UI shows a sticky bubble with the four action buttons —
     *      the user can come back to it after dismissing the push.
     *   2. kRPC emit (UserTaskCreated) so any active UI session
     *      reacts immediately.
     *   3. FCM + APNs push to all registered devices, with
     *      `category=LOGIN_CONSENT` so iOS / Apple Watch render the
     *      four action buttons natively.
     *
     * sourceUrn is `login_consent::<requestId>` — used to find and
     * resolve the task on respond / release / expiry.
     */
    private suspend fun sendConsentPush(holder: LoginConsentDocument.Holder) {
        val title = "JERVIS — login ${holder.label}"
        val body = "Pod potřebuje přihlásit ${holder.label}. OK teď, za 15 min, za 1 hod, nebo zrušit?"
        val connection = try {
            connectionRepository.getById(ConnectionId(ObjectId(holder.connectionId)))
        } catch (_: Exception) {
            null
        }
        val connectionName = connection?.name ?: holder.label
        val clientIds = resolveClientIds()

        for (cid in clientIds) {
            // 1. TaskDocument — sticky chat bubble so the user can come
            //    back to it after dismissing the push notification.
            try {
                val description = buildString {
                    appendLine(body)
                    appendLine()
                    appendLine("**Důvod:** ${holder.reason}")
                    appendLine("**Connection:** $connectionName")
                }
                val task = TaskDocument(
                    clientId = cid,
                    taskName = title,
                    content = description,
                    type = TaskTypeEnum.SYSTEM,
                    state = TaskStateEnum.USER_TASK,
                    sourceUrn = SourceUrn("login_consent::${holder.requestId}"),
                    pendingUserQuestion = title,
                    userQuestionContext = description,
                    priorityScore = 80, // higher than meeting_invite, lower than urgent_message
                    lastActivityAt = Instant.now(),
                    actionType = "login_consent",
                )
                taskRepository.save(task)

                notificationRpc.emitUserTaskCreated(
                    clientId = cid.toString(),
                    taskId = task.id.toString(),
                    title = title,
                    interruptAction = "login_consent",
                    interruptDescription = "requestId=${holder.requestId}|connectionId=${holder.connectionId}",
                    connectionName = connectionName,
                )
            } catch (e: Exception) {
                logger.warn { "LoginConsent task surface failed for $cid: ${e.message}" }
            }

            // 2. Push to phones / watches. Even though the chat bubble
            //    exists, the push is the primary UX (Apple Watch).
            val data = mapOf(
                "type" to "login_consent",
                "interruptAction" to "login_consent",
                "requestId" to holder.requestId,
                "connectionId" to holder.connectionId,
                "label" to holder.label,
                "category" to "LOGIN_CONSENT",
                "reason" to holder.reason,
            )
            try {
                fcmPushService.sendPushNotification(cid.toString(), title, body, data)
            } catch (e: Exception) {
                logger.warn { "LoginConsent FCM push failed for $cid: ${e.message}" }
            }
            try {
                apnsPushService.sendPushNotification(cid.toString(), title, body, data)
            } catch (e: Exception) {
                logger.warn { "LoginConsent APNs push failed for $cid: ${e.message}" }
            }
        }
    }

    /**
     * Resolve any open TaskDocument(s) tracking this consent request.
     * Called after the holder transitions to a terminal state
     * (granted-and-released, declined, expired). Drops the chat bubble
     * and emits UserTaskCancelled to UI subscribers.
     */
    private suspend fun resolveConsentTasks(requestId: String) {
        try {
            val urn = SourceUrn("login_consent::$requestId")
            val openTasks = taskRepository.findBySourceUrnAndStateIn(
                urn, listOf(TaskStateEnum.USER_TASK, TaskStateEnum.QUEUED, TaskStateEnum.NEW),
            ).toList()
            for (t in openTasks) {
                taskRepository.save(t.copy(state = TaskStateEnum.DONE, lastActivityAt = Instant.now()))
                try {
                    notificationRpc.emitUserTaskCancelled(
                        clientId = t.clientId.toString(),
                        taskId = t.id.toString(),
                        title = t.pendingUserQuestion ?: "Login consent",
                    )
                } catch (e: Exception) {
                    logger.warn { "LoginConsent task cancel emit failed: ${e.message}" }
                }
            }
        } catch (e: Exception) {
            logger.warn { "LoginConsent task resolve failed for $requestId: ${e.message}" }
        }
    }

    private suspend fun resolveClientIds(): List<ClientId> = try {
        deviceTokenRepository?.findAll()?.toList()
            ?.map { it.clientId }?.distinct()
            ?.map { ClientId(ObjectId(it)) }
            ?: listOf(ClientId(ObjectId("68a332361b04695a243e5ae8")))
    } catch (_: Exception) {
        listOf(ClientId(ObjectId("68a332361b04695a243e5ae8")))
    }
}
