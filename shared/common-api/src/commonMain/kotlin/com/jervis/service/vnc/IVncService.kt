package com.jervis.service.vnc

import com.jervis.dto.vnc.VncSessionSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * IVncService — kRPC surface for the sidebar Background VNC section.
 *
 * Discovery is push-based: `subscribeActiveSessions` returns a Flow
 * driven by a K8s `Watch<Pod>` over pods labelled
 * `app: jervis-browser-*` with a containerPort named `novnc`. No
 * polling — the watcher fires on every pod ADD / MODIFY / DELETE.
 *
 * Token mint is intentionally NOT on this service — it reuses the
 * existing `IConnectionService.getBrowserSessionStatus(connectionId)`
 * call (already used by the Settings VNC button). That endpoint returns
 * `BrowserSessionStatusDto.vncUrl` with a fresh one-shot token built
 * by the same path that powers the Settings flow — no NIH per
 * `feedback-no-quickfix` (max use of existing libs / endpoints).
 *
 * SSOT: `docs/vnc-sidebar-discovery.md`.
 */
@Rpc
interface IVncService {
    /**
     * Live list of VNC-capable browser pods, scoped by clientId when
     * provided. First emission is the current set; subsequent emissions
     * land on every K8s pod transition.
     *
     * @param clientId Optional scope filter — null = global view.
     */
    fun subscribeActiveSessions(clientId: String? = null): Flow<List<VncSessionSnapshot>>
}
