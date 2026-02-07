package com.jervis.service

import com.jervis.dto.coding.CodingAgentApiKeyUpdateDto
import com.jervis.dto.coding.CodingAgentOAuthUpdateDto
import com.jervis.dto.coding.CodingAgentSettingsDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface ICodingAgentSettingsService {
    suspend fun getSettings(): CodingAgentSettingsDto
    suspend fun updateApiKey(request: CodingAgentApiKeyUpdateDto): CodingAgentSettingsDto
    suspend fun updateOAuthCredentials(request: CodingAgentOAuthUpdateDto): CodingAgentSettingsDto
}
