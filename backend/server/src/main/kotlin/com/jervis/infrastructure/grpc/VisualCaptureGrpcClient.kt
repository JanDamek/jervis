package com.jervis.infrastructure.grpc

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.visual_capture.PtzGotoRequest
import com.jervis.contracts.visual_capture.PtzGotoResponse
import com.jervis.contracts.visual_capture.PtzPresetsRequest
import com.jervis.contracts.visual_capture.PtzPresetsResponse
import com.jervis.contracts.visual_capture.SnapshotRequest
import com.jervis.contracts.visual_capture.SnapshotResponse
import com.jervis.contracts.visual_capture.VisualCaptureServiceGrpcKt
import io.grpc.ManagedChannel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

// Kotlin-side client for jervis.visual_capture.VisualCaptureService.
// Used by ServerVisualCaptureGrpcImpl to proxy snapshot / PTZ calls
// from the orchestrator through to the jervis-visual-capture pod.
@Component
class VisualCaptureGrpcClient(
    @Qualifier(GrpcChannels.VISUAL_CAPTURE_CHANNEL) channel: ManagedChannel,
) {
    private val stub = VisualCaptureServiceGrpcKt
        .VisualCaptureServiceCoroutineStub(channel)

    private fun ctx(): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun snapshot(
        mode: String,
        preset: String,
        customPrompt: String,
    ): SnapshotResponse =
        stub.snapshot(
            SnapshotRequest.newBuilder()
                .setCtx(ctx())
                .setMode(mode)
                .setPreset(preset)
                .setCustomPrompt(customPrompt)
                .build(),
        )

    suspend fun ptzGoto(preset: String): PtzGotoResponse =
        stub.ptzGoto(
            PtzGotoRequest.newBuilder()
                .setCtx(ctx())
                .setPreset(preset)
                .build(),
        )

    suspend fun ptzPresets(): PtzPresetsResponse =
        stub.ptzPresets(PtzPresetsRequest.newBuilder().setCtx(ctx()).build())
}
