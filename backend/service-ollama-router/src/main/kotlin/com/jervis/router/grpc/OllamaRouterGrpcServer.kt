package com.jervis.router.grpc

import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import mu.KotlinLogging
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

class OllamaRouterGrpcServer(
    private val inferenceImpl: RouterInferenceGrpcImpl,
    private val adminImpl: RouterAdminGrpcImpl,
    private val port: Int,
) {
    private var server: Server? = null

    fun start() {
        server = NettyServerBuilder.forPort(port)
            .maxInboundMessageSize(64 * 1024 * 1024)
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(true)
            .addService(inferenceImpl)
            .addService(adminImpl)
            .addService(ProtoReflectionServiceV1.newInstance())
            .build()
            .also { it.start() }
        logger.info { "Ollama Router gRPC listening on :$port" }
    }

    fun stop() {
        runCatching {
            server?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
            server?.shutdownNow()
        }
    }
}
