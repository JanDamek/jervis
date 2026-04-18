package com.jervis.infrastructure.grpc

import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import jakarta.annotation.PreDestroy
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

// Central place to construct every outbound gRPC channel. Each pod-to-pod
// contracts client lives as a thin Spring @Component that depends on one
// of these named ManagedChannel beans and wraps a generated coroutine
// stub. One channel per target pod — pooling + connection reuse is the
// channel's job, not ours.
@Configuration
class GrpcChannels(
    @Value("\${grpc.ollama-router.host:jervis-ollama-router}")
    private val routerHost: String,
    @Value("\${grpc.ollama-router.port:5501}")
    private val routerPort: Int,
    @Value("\${grpc.knowledgebase.host:jervis-knowledgebase-write}")
    private val kbHost: String,
    @Value("\${grpc.knowledgebase.port:5501}")
    private val kbPort: Int,
) {
    private val logger = KotlinLogging.logger {}
    private val channels = mutableListOf<ManagedChannel>()

    @Bean(name = [ROUTER_ADMIN_CHANNEL])
    fun routerAdminChannel(): ManagedChannel {
        val channel = NettyChannelBuilder.forAddress(routerHost, routerPort)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()
        channels += channel
        logger.info { "gRPC channel → $routerHost:$routerPort (router admin)" }
        return channel
    }

    @Bean(name = [KNOWLEDGEBASE_CHANNEL])
    fun knowledgebaseChannel(): ManagedChannel {
        val channel = NettyChannelBuilder.forAddress(kbHost, kbPort)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()
        channels += channel
        logger.info { "gRPC channel → $kbHost:$kbPort (knowledgebase)" }
        return channel
    }

    @PreDestroy
    fun shutdown() {
        for (c in channels) {
            runCatching { c.shutdown().awaitTermination(5, TimeUnit.SECONDS) }
        }
    }

    companion object {
        const val ROUTER_ADMIN_CHANNEL = "routerAdminChannel"
        const val KNOWLEDGEBASE_CHANNEL = "knowledgebaseChannel"
    }
}
