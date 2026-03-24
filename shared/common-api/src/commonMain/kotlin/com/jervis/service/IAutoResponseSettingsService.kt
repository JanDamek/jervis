package com.jervis.service

import com.jervis.dto.AutoResponseDecisionDto
import com.jervis.dto.AutoResponseSettingsDto
import kotlinx.rpc.annotations.Rpc

/**
 * IAutoResponseSettingsService — RPC interface for auto-response configuration.
 *
 * Controls per-channel/project/client auto-response behavior.
 * Cascading resolution: channel → project → client → default(OFF).
 */
@Rpc
interface IAutoResponseSettingsService {
    /** Get settings for a specific scope. Returns null if no settings exist at this level. */
    suspend fun getSettings(
        clientId: String? = null,
        projectId: String? = null,
        channelType: String? = null,
        channelId: String? = null,
    ): AutoResponseSettingsDto?

    /** Save (create or update) settings. Returns the saved document ID. */
    suspend fun saveSettings(settings: AutoResponseSettingsDto): String

    /** Evaluate auto-response for a given context using cascading resolution. */
    suspend fun evaluate(
        clientId: String,
        projectId: String? = null,
        channelType: String,
        channelId: String? = null,
    ): AutoResponseDecisionDto
}
