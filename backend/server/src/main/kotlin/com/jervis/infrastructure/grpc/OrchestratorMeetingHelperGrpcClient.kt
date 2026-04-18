package com.jervis.infrastructure.grpc

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.orchestrator.HelperChunkRequest
import com.jervis.contracts.orchestrator.HelperChunkResponse
import com.jervis.contracts.orchestrator.HelperStatusRequest
import com.jervis.contracts.orchestrator.HelperStatusResponse
import com.jervis.contracts.orchestrator.OrchestratorMeetingHelperServiceGrpcKt
import com.jervis.contracts.orchestrator.StartHelperRequest
import com.jervis.contracts.orchestrator.StartHelperResponse
import com.jervis.contracts.orchestrator.StopHelperRequest
import com.jervis.contracts.orchestrator.StopHelperResponse
import io.grpc.ManagedChannel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

// Kotlin-side client for jervis.orchestrator.OrchestratorMeetingHelperService.
// Drives the live meeting assistance pipeline from the server while
// messages flow back via ServerMeetingHelperCallbacksService.
@Component
class OrchestratorMeetingHelperGrpcClient(
    @Qualifier(GrpcChannels.ORCHESTRATOR_CHANNEL) channel: ManagedChannel,
) {
    private val stub = OrchestratorMeetingHelperServiceGrpcKt
        .OrchestratorMeetingHelperServiceCoroutineStub(channel)

    private fun ctx(clientId: String = ""): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().setClientId(clientId).build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun start(
        meetingId: String,
        deviceId: String,
        sourceLang: String = "en",
        targetLang: String = "cs",
    ): StartHelperResponse =
        stub.start(
            StartHelperRequest.newBuilder()
                .setCtx(ctx())
                .setMeetingId(meetingId)
                .setDeviceId(deviceId)
                .setSourceLang(sourceLang)
                .setTargetLang(targetLang)
                .build(),
        )

    suspend fun stop(meetingId: String): StopHelperResponse =
        stub.stop(
            StopHelperRequest.newBuilder()
                .setCtx(ctx())
                .setMeetingId(meetingId)
                .build(),
        )

    suspend fun chunk(
        meetingId: String,
        text: String,
        speaker: String = "",
    ): HelperChunkResponse =
        stub.chunk(
            HelperChunkRequest.newBuilder()
                .setCtx(ctx())
                .setMeetingId(meetingId)
                .setText(text)
                .setSpeaker(speaker)
                .build(),
        )

    suspend fun status(meetingId: String): HelperStatusResponse =
        stub.status(
            HelperStatusRequest.newBuilder()
                .setCtx(ctx())
                .setMeetingId(meetingId)
                .build(),
        )
}
