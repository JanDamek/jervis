package com.jervis.service.preferences

import com.jervis.dto.preferences.SystemConfigDto
import com.jervis.dto.preferences.UpdateSystemConfigRequest
import kotlinx.rpc.annotations.Rpc

@Rpc
interface ISystemConfigService {
    /** Get current system configuration (returns defaults if never saved). */
    suspend fun getSystemConfig(): SystemConfigDto

    /** Update system configuration. Only non-null fields are updated. */
    suspend fun updateSystemConfig(request: UpdateSystemConfigRequest): SystemConfigDto
}
