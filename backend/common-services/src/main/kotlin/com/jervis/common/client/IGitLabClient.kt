package com.jervis.common.client

import com.jervis.dto.connection.ServiceCapabilitiesDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IGitLabClient {
    suspend fun getCapabilities(): ServiceCapabilitiesDto
}
