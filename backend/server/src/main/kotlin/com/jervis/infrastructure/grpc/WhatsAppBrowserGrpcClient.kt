package com.jervis.infrastructure.grpc

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.whatsapp_browser.InitSessionRequest
import com.jervis.contracts.whatsapp_browser.InitSessionResponse
import com.jervis.contracts.whatsapp_browser.LatestScrapeResponse
import com.jervis.contracts.whatsapp_browser.SessionRef
import com.jervis.contracts.whatsapp_browser.SessionStatus
import com.jervis.contracts.whatsapp_browser.TriggerScrapeRequest
import com.jervis.contracts.whatsapp_browser.TriggerScrapeResponse
import com.jervis.contracts.whatsapp_browser.VncTokenResponse
import com.jervis.contracts.whatsapp_browser.WhatsAppBrowserServiceGrpcKt
import io.grpc.ManagedChannel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * gRPC client for the cluster-wide `jervis-whatsapp-browser` pod.
 *
 * Single channel (see [GrpcChannels.WHATSAPP_BROWSER_CHANNEL]); the pod is
 * a StatefulSet with one replica serving all WhatsApp connections.
 */
@Component
class WhatsAppBrowserGrpcClient(
    @Qualifier(GrpcChannels.WHATSAPP_BROWSER_CHANNEL) channel: ManagedChannel,
) {
    private val stub = WhatsAppBrowserServiceGrpcKt.WhatsAppBrowserServiceCoroutineStub(channel)

    private fun ctx(): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun getSession(clientId: String): SessionStatus =
        stub.getSession(SessionRef.newBuilder().setCtx(ctx()).setClientId(clientId).build())

    suspend fun initSession(
        clientId: String,
        loginUrl: String = "https://web.whatsapp.com",
        capabilities: List<String> = listOf("CHAT_READ"),
        phoneNumber: String? = null,
        userAgent: String? = null,
    ): InitSessionResponse =
        stub.initSession(
            InitSessionRequest.newBuilder()
                .setCtx(ctx())
                .setClientId(clientId)
                .setLoginUrl(loginUrl)
                .setUserAgent(userAgent ?: "")
                .addAllCapabilities(capabilities)
                .setPhoneNumber(phoneNumber ?: "")
                .build(),
        )

    suspend fun triggerScrape(
        clientId: String,
        maxTier: String,
        processingMode: String,
    ): TriggerScrapeResponse =
        stub.triggerScrape(
            TriggerScrapeRequest.newBuilder()
                .setCtx(ctx())
                .setClientId(clientId)
                .setMaxTier(maxTier)
                .setProcessingMode(processingMode)
                .build(),
        )

    suspend fun getLatestScrape(clientId: String): LatestScrapeResponse =
        stub.getLatestScrape(SessionRef.newBuilder().setCtx(ctx()).setClientId(clientId).build())

    suspend fun createVncToken(clientId: String): VncTokenResponse =
        stub.createVncToken(SessionRef.newBuilder().setCtx(ctx()).setClientId(clientId).build())
}
