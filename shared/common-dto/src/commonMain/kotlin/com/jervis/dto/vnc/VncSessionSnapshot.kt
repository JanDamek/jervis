package com.jervis.dto.vnc

import kotlinx.serialization.Serializable

/**
 * UI-facing snapshot of one VNC-capable browser pod — sidebar Background
 * (Fáze K) shows these as `🖥️ <connectionLabel> — <pod>` rows.
 *
 * Discovery: `IVncService.subscribeActiveSessions` scans K8s pods with
 * label `app: jervis-browser-<connectionId>` and a containerPort named
 * `novnc`. Snapshots are pushed via K8s `Watch<Pod>` — no polling.
 *
 * Click-to-open: row click triggers `IVncService.mintVncSession(connectionId)`
 * which returns a one-shot token URL. UI either embeds via WebView or
 * opens externally — see `docs/vnc-sidebar-discovery.md`.
 */
@Serializable
data class VncSessionSnapshot(
    val connectionId: String,
    val connectionLabel: String,
    val clientId: String? = null,
    val podName: String,
    /**
     * `true` whenever the UI must call `mintVncSession(connectionId)` to
     * obtain a fresh one-shot URL before opening (default for browser
     * pods — token has 5 min TTL so we mint on demand).
     */
    val requiresMint: Boolean = true,
    /**
     * Optional placeholder note. Used today for the WhatsApp pod which
     * still uses the legacy ingress and will be migrated to the
     * browser-pod convention as a separate task.
     */
    val note: String? = null,
)
