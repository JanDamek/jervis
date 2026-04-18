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
    @Value("\${grpc.orchestrator.host:jervis-orchestrator}")
    private val orchestratorHost: String,
    @Value("\${grpc.orchestrator.port:5501}")
    private val orchestratorPort: Int,
    @Value("\${grpc.visual-capture.host:jervis-visual-capture}")
    private val visualCaptureHost: String,
    @Value("\${grpc.visual-capture.port:5501}")
    private val visualCapturePort: Int,
    @Value("\${grpc.o365-gateway.host:jervis-o365-gateway}")
    private val o365GatewayHost: String,
    @Value("\${grpc.o365-gateway.port:5501}")
    private val o365GatewayPort: Int,
    @Value("\${grpc.meeting-attender.host:jervis-meeting-attender}")
    private val meetingAttenderHost: String,
    @Value("\${grpc.meeting-attender.port:5501}")
    private val meetingAttenderPort: Int,
    @Value("\${grpc.correction.host:jervis-correction}")
    private val correctionHost: String,
    @Value("\${grpc.correction.port:5501}")
    private val correctionPort: Int,
    @Value("\${grpc.whatsapp-browser.host:jervis-whatsapp-browser}")
    private val whatsappBrowserHost: String,
    @Value("\${grpc.whatsapp-browser.port:5501}")
    private val whatsappBrowserPort: Int,
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
        // 64 MiB inbound + outbound message size covers email + meeting
        // attachments. Larger payloads should go via the blob side channel
        // (see docs/inter-service-contracts-bigbang.md §2.3).
        val maxMsgBytes = 64 * 1024 * 1024
        val channel = NettyChannelBuilder.forAddress(kbHost, kbPort)
            .usePlaintext()
            .maxInboundMessageSize(maxMsgBytes)
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

    @Bean(name = [ORCHESTRATOR_CHANNEL])
    fun orchestratorChannel(): ManagedChannel {
        val maxMsgBytes = 64 * 1024 * 1024
        val channel = NettyChannelBuilder.forAddress(orchestratorHost, orchestratorPort)
            .usePlaintext()
            .maxInboundMessageSize(maxMsgBytes)
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()
        channels += channel
        logger.info { "gRPC channel → $orchestratorHost:$orchestratorPort (orchestrator)" }
        return channel
    }

    @Bean(name = [VISUAL_CAPTURE_CHANNEL])
    fun visualCaptureChannel(): ManagedChannel {
        val channel = NettyChannelBuilder.forAddress(visualCaptureHost, visualCapturePort)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()
        channels += channel
        logger.info { "gRPC channel → $visualCaptureHost:$visualCapturePort (visual-capture)" }
        return channel
    }

    @Bean(name = [O365_GATEWAY_CHANNEL])
    fun o365GatewayChannel(): ManagedChannel {
        val channel = NettyChannelBuilder.forAddress(o365GatewayHost, o365GatewayPort)
            .usePlaintext()
            .maxInboundMessageSize(64 * 1024 * 1024)
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()
        channels += channel
        logger.info { "gRPC channel → $o365GatewayHost:$o365GatewayPort (o365-gateway)" }
        return channel
    }

    @Bean(name = [MEETING_ATTENDER_CHANNEL])
    fun meetingAttenderChannel(): ManagedChannel {
        val channel = NettyChannelBuilder.forAddress(meetingAttenderHost, meetingAttenderPort)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()
        channels += channel
        logger.info { "gRPC channel → $meetingAttenderHost:$meetingAttenderPort (meeting-attender)" }
        return channel
    }

    @Bean(name = [CORRECTION_CHANNEL])
    fun correctionChannel(): ManagedChannel {
        val channel = NettyChannelBuilder.forAddress(correctionHost, correctionPort)
            .usePlaintext()
            .maxInboundMessageSize(64 * 1024 * 1024)
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()
        channels += channel
        logger.info { "gRPC channel → $correctionHost:$correctionPort (correction)" }
        return channel
    }

    @Bean(name = [WHATSAPP_BROWSER_CHANNEL])
    fun whatsappBrowserChannel(): ManagedChannel {
        val channel = NettyChannelBuilder.forAddress(whatsappBrowserHost, whatsappBrowserPort)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()
        channels += channel
        logger.info { "gRPC channel → $whatsappBrowserHost:$whatsappBrowserPort (whatsapp-browser)" }
        return channel
    }

    companion object {
        const val ROUTER_ADMIN_CHANNEL = "routerAdminChannel"
        const val KNOWLEDGEBASE_CHANNEL = "knowledgebaseChannel"
        const val ORCHESTRATOR_CHANNEL = "orchestratorChannel"
        const val VISUAL_CAPTURE_CHANNEL = "visualCaptureChannel"
        const val O365_GATEWAY_CHANNEL = "o365GatewayChannel"
        const val MEETING_ATTENDER_CHANNEL = "meetingAttenderChannel"
        const val CORRECTION_CHANNEL = "correctionChannel"
        const val WHATSAPP_BROWSER_CHANNEL = "whatsappBrowserChannel"
    }
}
