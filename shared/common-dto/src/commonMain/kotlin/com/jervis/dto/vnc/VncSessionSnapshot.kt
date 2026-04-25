package com.jervis.dto.vnc

import kotlinx.serialization.Serializable

/**
 * UI-facing snapshot of one active VNC session — sidebar Background
 * (Fáze K) shows these as `🖥️ <connectionLabel> → <pod>` entries with
 * a click-to-open embed.
 *
 * `vncUrl` is the noVNC entrypoint behind the `vnc-router` ingress. The
 * URL never carries the password (per `feedback-vnc-no-password-in-url`);
 * the session cookie carries the auth.
 */
@Serializable
data class VncSessionSnapshot(
    val tokenId: String,
    val connectionId: String,
    val connectionLabel: String,
    val clientId: String,
    val podName: String,
    /** ISO-8601 — when the user actually consumed the handoff token. */
    val sessionStartedAt: String,
    /** ISO-8601 — when the session cookie expires. */
    val sessionExpiresAt: String,
    val vncUrl: String,
)
