package com.jervis.infrastructure.grpc

import com.jervis.common.types.ConnectionId
import com.jervis.connection.BrowserPodManager
import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.o365_browser_pool.HealthRequest
import com.jervis.contracts.o365_browser_pool.HealthResponse
import com.jervis.contracts.o365_browser_pool.InitSessionRequest
import com.jervis.contracts.o365_browser_pool.InitSessionResponse
import com.jervis.contracts.o365_browser_pool.InstructionRequest
import com.jervis.contracts.o365_browser_pool.InstructionResponse
import com.jervis.contracts.o365_browser_pool.O365BrowserPoolServiceGrpcKt
import com.jervis.contracts.o365_browser_pool.SessionRef
import com.jervis.contracts.o365_browser_pool.SessionStatus
import com.jervis.contracts.o365_browser_pool.SubmitMfaRequest
import com.jervis.contracts.o365_browser_pool.VncTokenResponse
import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import jakarta.annotation.PreDestroy
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * gRPC client for the per-connection `jervis-o365-browser-pool` pods.
 *
 * Channels are cached per [ConnectionId] because each connection gets
 * its own Deployment + Service — host resolves through
 * [BrowserPodManager.serviceUrl]. The service port for gRPC is 5501
 * (overridable via `grpc.o365-browser-pool.port`).
 */
@Component
class O365BrowserPoolGrpcClient(
    @Value("\${grpc.o365-browser-pool.port:5501}")
    private val grpcPort: Int,
) {
    private val channels = ConcurrentHashMap<ObjectId, ManagedChannel>()

    private fun channelFor(connectionId: ConnectionId): ManagedChannel =
        channels.computeIfAbsent(connectionId.value) {
            val host = BrowserPodManager.grpcHost(connectionId)
            NettyChannelBuilder.forAddress(host, grpcPort)
                .usePlaintext()
                .maxInboundMessageSize(64 * 1024 * 1024)
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(5, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build()
                .also { logger.info { "gRPC channel → $host:$grpcPort (o365-browser-pool:$connectionId)" } }
        }

    private fun stub(connectionId: ConnectionId): O365BrowserPoolServiceGrpcKt.O365BrowserPoolServiceCoroutineStub =
        O365BrowserPoolServiceGrpcKt.O365BrowserPoolServiceCoroutineStub(channelFor(connectionId))

    private fun ctx(): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun health(connectionId: ConnectionId): HealthResponse =
        stub(connectionId).health(HealthRequest.newBuilder().setCtx(ctx()).build())

    suspend fun getSession(connectionId: ConnectionId, clientId: String): SessionStatus =
        stub(connectionId).getSession(
            SessionRef.newBuilder().setCtx(ctx()).setClientId(clientId).build(),
        )

    suspend fun initSession(
        connectionId: ConnectionId,
        clientId: String,
        loginUrl: String,
        capabilities: List<String>,
        username: String?,
        password: String?,
        userAgent: String? = null,
    ): InitSessionResponse =
        stub(connectionId).initSession(
            InitSessionRequest.newBuilder()
                .setCtx(ctx())
                .setClientId(clientId)
                .setLoginUrl(loginUrl)
                .setUserAgent(userAgent ?: "")
                .addAllCapabilities(capabilities)
                .setUsername(username ?: "")
                .setPassword(password ?: "")
                .build(),
        )

    suspend fun submitMfa(
        connectionId: ConnectionId,
        clientId: String,
        code: String,
    ): InitSessionResponse =
        stub(connectionId).submitMfa(
            SubmitMfaRequest.newBuilder()
                .setCtx(ctx())
                .setClientId(clientId)
                .setCode(code)
                .build(),
        )

    suspend fun createVncToken(connectionId: ConnectionId, clientId: String): VncTokenResponse =
        stub(connectionId).createVncToken(
            SessionRef.newBuilder().setCtx(ctx()).setClientId(clientId).build(),
        )

    suspend fun pushInstruction(
        connectionId: ConnectionId,
        clientId: String,
        instruction: String,
    ): InstructionResponse =
        stub(connectionId).pushInstruction(
            InstructionRequest.newBuilder()
                .setCtx(ctx())
                .setClientId(clientId)
                .setInstruction(instruction)
                .build(),
        )

    /** Invalidate the cached channel (called when a connection is deleted). */
    fun dropChannel(connectionId: ConnectionId) {
        channels.remove(connectionId.value)?.let {
            runCatching { it.shutdown().awaitTermination(2, TimeUnit.SECONDS) }
        }
    }

    @PreDestroy
    fun shutdown() {
        channels.values.forEach {
            runCatching { it.shutdown().awaitTermination(5, TimeUnit.SECONDS) }
        }
        channels.clear()
    }
}
