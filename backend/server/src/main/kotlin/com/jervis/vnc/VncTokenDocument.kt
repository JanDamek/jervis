package com.jervis.vnc

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * VNC access token — single-use handoff that lets the noVNC router bind the
 * browser tab to a specific connection's browser pod, then upgrades to a
 * session cookie for the rest of the tab's lifetime.
 *
 * Wire format: `{connectionId}_{randomHex}`. The vnc-router nginx reads
 * `connectionId` from the prefix to pick the upstream pod; this document
 * only governs auth.
 *
 * Lifecycle:
 *   1. Server issues token → `consumed=false`, TTL index expires after 5 min.
 *   2. Browser hits `/vnc-login?token=...` → nginx auth_request → server
 *      flips `consumed=true` and issues a `sessionId`.
 *   3. Follow-up WS upgrades carry the `vnc_session` cookie → server
 *      validates against `sessionId` + `sessionExpiresAt`.
 */
@Document(collection = "vnc_tokens")
data class VncTokenDocument(
    @Id
    val id: ObjectId = ObjectId(),
    @Indexed(unique = true)
    val token: String,
    val connectionId: String,
    val clientId: String,
    @Indexed(expireAfterSeconds = 0)
    val expiresAt: Instant,
    val consumed: Boolean = false,
    val consumedAt: Instant? = null,
    val sessionId: String? = null,
    val sessionExpiresAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
)
