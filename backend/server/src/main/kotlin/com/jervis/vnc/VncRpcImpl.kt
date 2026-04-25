package com.jervis.vnc

import com.jervis.dto.vnc.VncSessionSnapshot
import com.jervis.service.vnc.IVncService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class VncRpcImpl(
    private val template: ReactiveMongoTemplate,
    private val connectionRepository: com.jervis.connection.ConnectionRepository,
    @Value("\${jervis.vnc.publicBaseUrl:https://jervis-vnc.damek-soft.eu}")
    private val vncPublicBaseUrl: String,
) : IVncService {
    private val logger = KotlinLogging.logger {}

    /**
     * Push stream of currently active VNC sessions. Uses periodic polling
     * (every 5 s) of `vnc_tokens` filtered to consumed-and-not-expired
     * tokens. Mongo change streams would be cleaner but require a replica
     * set; the polling cadence is fine for the expected cardinality
     * (a handful of concurrent VNC sessions across the cluster).
     *
     * The flow only emits when the active set actually changes — same
     * snapshot twice in a row is suppressed to avoid clutter on the UI
     * collector.
     */
    override fun subscribeActiveSessions(clientId: String?): Flow<List<VncSessionSnapshot>> = flow {
        var lastFingerprint = ""
        while (true) {
            val snapshot = currentSessions(clientId)
            val fingerprint = snapshot.joinToString("|") { "${it.tokenId}@${it.sessionExpiresAt}" }
            if (fingerprint != lastFingerprint) {
                emit(snapshot)
                lastFingerprint = fingerprint
            }
            delay(5_000)
        }
    }

    private suspend fun currentSessions(clientId: String?): List<VncSessionSnapshot> {
        val now = Instant.now()
        val criteria = Criteria.where("consumed").`is`(true)
            .and("sessionExpiresAt").gt(now)
        if (clientId != null) {
            criteria.and("clientId").`is`(clientId)
        }
        val tokens = try {
            template
                .find(Query(criteria), VncTokenDocument::class.java, "vnc_tokens")
                .collectList()
                .awaitSingle()
        } catch (e: Exception) {
            logger.warn { "VncRpcImpl | query failed: ${e.message}" }
            return emptyList()
        }
        return tokens.map { token ->
            val connection = runCatching {
                connectionRepository.getById(com.jervis.common.types.ConnectionId.fromString(token.connectionId))
            }.getOrNull()
            VncSessionSnapshot(
                tokenId = token.token,
                connectionId = token.connectionId,
                connectionLabel = connection?.name ?: token.connectionId,
                clientId = token.clientId,
                podName = "browser-pod-${token.connectionId}",
                sessionStartedAt = (token.consumedAt ?: token.createdAt).toString(),
                sessionExpiresAt = (token.sessionExpiresAt ?: token.expiresAt).toString(),
                vncUrl = "${vncPublicBaseUrl.trimEnd('/')}/vnc-login?token=${token.token}",
            )
        }
    }
}
