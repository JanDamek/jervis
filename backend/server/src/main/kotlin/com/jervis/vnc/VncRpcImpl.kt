package com.jervis.vnc

import com.jervis.common.types.ConnectionId
import com.jervis.connection.ConnectionDocument
import com.jervis.connection.ConnectionRepository
import com.jervis.dto.connection.ProviderEnum
import com.jervis.dto.vnc.VncSessionSnapshot
import com.jervis.service.vnc.IVncService
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.Watch
import io.fabric8.kubernetes.client.Watcher
import io.fabric8.kubernetes.client.WatcherException
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * VNC sidebar service — pod discovery + on-demand token mint.
 *
 * SSOT: `docs/vnc-sidebar-discovery.md`. Replaces the previous
 * `vnc_tokens`-only implementation: that only ever surfaced sessions
 * after a token was already consumed, which never happened from the
 * UI sidebar (tokens are minted on click, not on listing).
 *
 * Discovery: K8s `Watch<Pod>` on the `jervis` namespace, filtered by
 * label `app: jervis-browser-*` (Teams/O365 browser pool) and
 * `app: jervis-whatsapp-browser` (placeholder until the WhatsApp
 * migration). Push-based — no polling. Initial state is loaded from a
 * one-shot list call before the watch opens.
 *
 * Token mint: `mintVncSession(connectionId)` creates a fresh
 * `VncTokenDocument` (TTL 5 min, consumed=false) and returns the
 * vnc-router URL with the token in the query string. The token is
 * one-shot — first request to the router consumes it and sets a
 * `vnc_session` cookie. UI must open / embed the URL within the TTL.
 */
@Component
class VncRpcImpl(
    private val template: ReactiveMongoTemplate,
    private val connectionRepository: ConnectionRepository,
) : IVncService {
    private val logger = KotlinLogging.logger {}
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    @Volatile
    private var k8sClient: KubernetesClient? = null

    @Volatile
    private var watch: Watch? = null

    /** podName → snapshot. Source of truth for emit. */
    private val sessionsByPodName = ConcurrentHashMap<String, VncSessionSnapshot>()

    private val updates = MutableSharedFlow<List<VncSessionSnapshot>>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @PostConstruct
    fun start() {
        scope.launch {
            runCatching { initialScan() }
                .onFailure { logger.error(it) { "VNC initial scan failed" } }
            runCatching { openWatch() }
                .onFailure { logger.error(it) { "VNC pod watch open failed" } }
        }
        logger.info { "VncRpcImpl started — K8s pod discovery + on-demand mint" }
    }

    @PreDestroy
    fun stop() {
        runCatching { watch?.close() }
        runCatching { k8sClient?.close() }
        scope.cancel()
    }

    override fun subscribeActiveSessions(clientId: String?): Flow<List<VncSessionSnapshot>> =
        updates.asSharedFlow()
            .onStart { emit(buildSnapshot()) }
            .map { all ->
                if (clientId == null) all
                else all.filter { it.clientId == null || it.clientId == clientId }
            }

    // ── K8s discovery ─────────────────────────────────────────────────

    private suspend fun initialScan() {
        val client = ensureClient()
        val pods = withContext(Dispatchers.IO) {
            client.pods().inNamespace(NAMESPACE).list().items
        }
        for (pod in pods) {
            if (matchesBrowserLabel(pod)) {
                updateFromPod(pod)
            }
        }
        emitSnapshot()
        logger.info { "VNC initial scan | tracked=${sessionsByPodName.size}" }
    }

    private fun openWatch() {
        val client = ensureClient()
        watch = client.pods()
            .inNamespace(NAMESPACE)
            .watch(object : Watcher<Pod> {
                override fun eventReceived(action: Watcher.Action, pod: Pod) {
                    if (!matchesBrowserLabel(pod)) return
                    val podName = pod.metadata?.name ?: return
                    if (action == Watcher.Action.DELETED) {
                        sessionsByPodName.remove(podName)
                        scope.launch { emitSnapshot() }
                    } else if (action == Watcher.Action.ADDED || action == Watcher.Action.MODIFIED) {
                        scope.launch {
                            updateFromPod(pod)
                            emitSnapshot()
                        }
                    }
                }

                override fun onClose(cause: WatcherException?) {
                    if (cause != null) {
                        logger.warn(cause) { "VNC pod watch closed unexpectedly — reopening" }
                    } else {
                        logger.info { "VNC pod watch closed — reopening" }
                    }
                    scope.launch {
                        runCatching { openWatch() }
                            .onFailure { logger.error(it) { "VNC pod watch reopen failed" } }
                    }
                }
            })
        logger.info { "VNC pod watch open | namespace=$NAMESPACE" }
    }

    private fun matchesBrowserLabel(pod: Pod): Boolean {
        val app = pod.metadata?.labels?.get("app") ?: return false
        return app.startsWith(BROWSER_POD_PREFIX) || app == WHATSAPP_POD_APP
    }

    private suspend fun updateFromPod(pod: Pod) {
        val podName = pod.metadata?.name ?: return
        val app = pod.metadata?.labels?.get("app") ?: return
        val ready = pod.status?.containerStatuses?.all { it.ready == true } == true
        val hasNovncPort = pod.spec?.containers?.any { c ->
            c.ports?.any { it.name == "novnc" } == true
        } == true
        if (!ready || !hasNovncPort) {
            sessionsByPodName.remove(podName)
            return
        }
        val snap = when {
            app == WHATSAPP_POD_APP -> buildWhatsAppPlaceholder(podName)
            app.startsWith(BROWSER_POD_PREFIX) -> buildBrowserPodSnapshot(app, podName)
            else -> null
        }
        if (snap != null) sessionsByPodName[podName] = snap
    }

    private suspend fun buildBrowserPodSnapshot(app: String, podName: String): VncSessionSnapshot {
        val connectionId = app.removePrefix(BROWSER_POD_PREFIX)
        val connection = runCatching {
            connectionRepository.getById(ConnectionId.fromString(connectionId))
        }.getOrNull()
        return VncSessionSnapshot(
            connectionId = connectionId,
            connectionLabel = connection?.name ?: connectionId,
            // ConnectionDocument is not client-scoped — sidebar Background is
            // GLOBAL by design. Leave clientId null so the optional client
            // filter on subscribeActiveSessions is a no-op (every snapshot
            // matches when caller passes null).
            clientId = null,
            podName = podName,
            requiresMint = true,
            note = null,
        )
    }

    private suspend fun buildWhatsAppPlaceholder(podName: String): VncSessionSnapshot {
        val whatsAppConnection = runCatching {
            template.find(
                Query(Criteria.where("provider").`is`(ProviderEnum.WHATSAPP.name)),
                ConnectionDocument::class.java,
                "connections",
            ).collectList().awaitSingle().firstOrNull()
        }.getOrNull()
        return VncSessionSnapshot(
            connectionId = whatsAppConnection?.id?.toString() ?: "whatsapp-placeholder",
            connectionLabel = whatsAppConnection?.name ?: "WhatsApp",
            clientId = null,
            podName = podName,
            requiresMint = false,
            note = "WhatsApp VNC bude přepsán na browser-pod konvenci jako Teams. " +
                "Zatím jen placeholder — žádný embed.",
        )
    }

    private suspend fun emitSnapshot() {
        updates.emit(buildSnapshot())
    }

    private fun buildSnapshot(): List<VncSessionSnapshot> =
        sessionsByPodName.values.toList().sortedBy { it.connectionLabel.lowercase() }

    private fun ensureClient(): KubernetesClient {
        val existing = k8sClient
        if (existing != null) return existing
        synchronized(this) {
            val stillExisting = k8sClient
            if (stillExisting != null) return stillExisting
            val cfg = ConfigBuilder()
                .withRequestTimeout(30_000)
                .withConnectionTimeout(10_000)
                .build()
            val created = KubernetesClientBuilder().withConfig(cfg).build()
            k8sClient = created
            return created
        }
    }

    companion object {
        private const val NAMESPACE = "jervis"
        private const val BROWSER_POD_PREFIX = "jervis-browser-"
        private const val WHATSAPP_POD_APP = "jervis-whatsapp-browser"
    }
}
