package com.jervis.common.client

import com.jervis.dto.connection.AuthTypeEnum
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionResourceDto
import com.jervis.dto.connection.ConnectionTestResultDto
import com.jervis.dto.connection.ProtocolEnum
import com.jervis.dto.connection.ProviderDescriptor
import kotlinx.rpc.annotations.Rpc
import kotlinx.serialization.Serializable

@Rpc
interface IProviderService {
    suspend fun getDescriptor(): ProviderDescriptor
    suspend fun testConnection(request: ProviderTestRequest): ConnectionTestResultDto
    suspend fun listResources(request: ProviderListResourcesRequest): List<ConnectionResourceDto>
}

@Serializable
data class ProviderTestRequest(
    val baseUrl: String = "",
    val protocol: ProtocolEnum = ProtocolEnum.HTTP,
    val authType: AuthTypeEnum = AuthTypeEnum.NONE,
    val username: String? = null,
    val password: String? = null,
    val bearerToken: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val useSsl: Boolean? = null,
    val useTls: Boolean? = null,
    val folderName: String? = null,
)

@Serializable
data class ProviderListResourcesRequest(
    val baseUrl: String = "",
    val protocol: ProtocolEnum = ProtocolEnum.HTTP,
    val authType: AuthTypeEnum = AuthTypeEnum.NONE,
    val username: String? = null,
    val password: String? = null,
    val bearerToken: String? = null,
    val capability: ConnectionCapability,
    val host: String? = null,
    val port: Int? = null,
    val useSsl: Boolean? = null,
    val useTls: Boolean? = null,
)
