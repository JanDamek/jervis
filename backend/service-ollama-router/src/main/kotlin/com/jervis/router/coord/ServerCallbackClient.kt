package com.jervis.router.coord

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.server.GetOpenRouterSettingsRequest
import com.jervis.contracts.server.OpenRouterSettings
import com.jervis.contracts.server.PersistModelStatsRequest
import com.jervis.contracts.server.PersistModelStatsResponse
import com.jervis.contracts.server.ServerOpenRouterSettingsServiceGrpcKt
import com.jervis.contracts.server.GpuIdleRequest
import com.jervis.contracts.server.GpuIdleResponse
import com.jervis.contracts.server.ServerGpuIdleServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import mu.KotlinLogging
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * gRPC client for router → Kotlin server reverse callbacks. Mirrors Python
 * `grpc_server_client.py` keepalive settings (30s/10s, permit-without-calls).
 *
 * Note: communication direction is router (Kotlin) → server (Kotlin). Per
 * the codebase guideline this should ideally be kRPC; we keep gRPC for the
 * big-bang cutover because the proto contracts and server-side
 * `ServerOpenRouterSettingsGrpcImpl` already exist 1:1 from the Python era.
 * A follow-up slice will migrate this to a kRPC interface — tracked in KB
 * once the Kotlin port is live.
 */
class ServerCallbackClient(
    serverHost: String,
    serverPort: Int,
) {
    // `grpc-netty-shaded` registers only UdsNameResolverProvider (unix://);
    // it lacks DnsNameResolverProvider. Bypass the gRPC NameResolver layer
    // by passing a resolved `InetSocketAddress` — Java's standard DNS
    // resolution handles `jervis-server` (Kubernetes service DNS).
    private val socketAddress: java.net.SocketAddress =
        java.net.InetSocketAddress(serverHost, serverPort)

    private val channel: ManagedChannel = NettyChannelBuilder
        .forAddress(socketAddress)
        .usePlaintext()
        .keepAliveTime(30, TimeUnit.SECONDS)
        .keepAliveTimeout(10, TimeUnit.SECONDS)
        .keepAliveWithoutCalls(true)
        .maxInboundMessageSize(32 * 1024 * 1024)
        .build()

    private val openrouterStub = ServerOpenRouterSettingsServiceGrpcKt
        .ServerOpenRouterSettingsServiceCoroutineStub(channel)
    private val gpuIdleStub = ServerGpuIdleServiceGrpcKt
        .ServerGpuIdleServiceCoroutineStub(channel)

    init {
        logger.info { "Server callback gRPC channel opened to $serverHost:$serverPort" }
    }

    private fun ctx(): RequestContext = RequestContext.newBuilder()
        .setRequestId("")
        .putTrace("caller", "ollama-router")
        .build()

    suspend fun getOpenRouterSettings(): OpenRouterSettings? = runCatching {
        openrouterStub.getSettings(GetOpenRouterSettingsRequest.newBuilder().setCtx(ctx()).build())
    }.onFailure { logger.warn { "getOpenRouterSettings failed: ${it.message}" } }.getOrNull()

    suspend fun persistModelStats(req: PersistModelStatsRequest): PersistModelStatsResponse? = runCatching {
        openrouterStub.persistModelStats(req)
    }.onFailure { logger.warn { "persistModelStats failed: ${it.message}" } }.getOrNull()

    suspend fun gpuIdle(idleSeconds: Long): GpuIdleResponse? = runCatching {
        gpuIdleStub.gpuIdle(
            GpuIdleRequest.newBuilder().setCtx(ctx()).setIdleSeconds(idleSeconds).build(),
        )
    }.onFailure { logger.debug { "gpuIdle callback failed: ${it.message}" } }.getOrNull()

    fun close() {
        runCatching {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
            channel.shutdownNow()
        }
    }
}
