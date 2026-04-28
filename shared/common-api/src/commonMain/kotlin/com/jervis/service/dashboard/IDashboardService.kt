package com.jervis.service.dashboard

import com.jervis.dto.dashboard.DashboardSnapshotDto
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * IDashboardService — kRPC push surface for the desktop UI Dashboard
 * screen.
 *
 * The server owns a `MutableSharedFlow<DashboardSnapshotDto>(replay=1)`
 * which is fed by a 5-second pull from the Python orchestrator's
 * SessionBroker (the broker is in-process Python state with no
 * server-streaming push channel today; pull cadence is server-internal
 * and does not break the push-only UI contract — rule #9).
 *
 * First emission is the latest snapshot (replay=1); subsequent emissions
 * are fresh snapshots whenever the orchestrator pull tick produces new
 * data. UI never pulls.
 */
@Rpc
interface IDashboardService {
    /** Push stream of orchestrator session snapshots. */
    fun subscribeSessionSnapshot(): Flow<DashboardSnapshotDto>
}
