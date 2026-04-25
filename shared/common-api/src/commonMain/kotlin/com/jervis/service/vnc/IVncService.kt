package com.jervis.service.vnc

import com.jervis.dto.vnc.VncSessionSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * IVncService — kRPC push surface for active VNC sessions.
 *
 * Sidebar Background section (Fáze K) renders one entry per active
 * session; clicking embeds the noVNC viewer for that pod's display.
 *
 * Live updates come from the change stream over `vnc_tokens`
 * (consumption flips a token from "issued" to "session active") plus
 * a periodic K8s pod presence cross-check so a torn-down pod
 * disappears from the list within seconds.
 */
@Rpc
interface IVncService {
    /**
     * Replay-1 Flow of currently active VNC sessions. First emission
     * is the snapshot at subscribe time; subsequent emissions land on
     * any token consumption / expiry / pod presence change.
     *
     * @param clientId Optional scope filter — null = global view.
     */
    fun subscribeActiveSessions(clientId: String? = null): Flow<List<VncSessionSnapshot>>
}
