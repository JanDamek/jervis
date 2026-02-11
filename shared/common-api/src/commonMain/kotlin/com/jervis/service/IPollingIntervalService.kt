package com.jervis.service

import com.jervis.dto.indexing.PollingIntervalSettingsDto
import com.jervis.dto.indexing.PollingIntervalUpdateDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IPollingIntervalService {
    /** Get current polling interval settings (returns defaults if never saved). */
    suspend fun getSettings(): PollingIntervalSettingsDto

    /** Update polling intervals. Only provided capabilities are updated. */
    suspend fun updateSettings(request: PollingIntervalUpdateDto): PollingIntervalSettingsDto

    /** Trigger immediate poll for a specific capability across all connections. */
    suspend fun triggerPollNow(capability: String): Boolean
}
