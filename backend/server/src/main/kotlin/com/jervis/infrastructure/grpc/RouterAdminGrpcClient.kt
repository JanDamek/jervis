package com.jervis.infrastructure.grpc

import com.jervis.contracts.common.Scope
import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.TierCap
import com.jervis.contracts.router.InvalidateClientTierRequest
import com.jervis.contracts.router.ListModelErrorsRequest
import com.jervis.contracts.router.ListModelStatsRequest
import com.jervis.contracts.router.MaxContextRequest
import com.jervis.contracts.router.MaxContextResponse
import com.jervis.contracts.router.ModelErrorInfo
import com.jervis.contracts.router.ModelStatInfo
import com.jervis.contracts.router.RateLimitsRequest
import com.jervis.contracts.router.ReportModelErrorRequest
import com.jervis.contracts.router.ReportModelErrorResponse
import com.jervis.contracts.router.ReportModelSuccessRequest
import com.jervis.contracts.router.ResetModelErrorRequest
import com.jervis.contracts.router.RouterAdminServiceGrpcKt
import com.jervis.contracts.router.TestModelRequest
import com.jervis.contracts.router.TestModelResponse
import io.grpc.ManagedChannel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

// Kotlin-side client for jervis.router.RouterAdminService. Every method
// constructs a RequestContext with a fresh request_id so log trails match
// server-side records; the ctx scope stays empty for admin calls because
// they are global (not scoped to a client).
@Component
class RouterAdminGrpcClient(
    @Qualifier(GrpcChannels.ROUTER_ADMIN_CHANNEL) channel: ManagedChannel,
) {
    private val stub = RouterAdminServiceGrpcKt.RouterAdminServiceCoroutineStub(channel)

    private fun ctx(clientId: String = ""): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().setClientId(clientId).build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun getMaxContext(maxTier: TierCap = TierCap.TIER_CAP_UNSPECIFIED): MaxContextResponse =
        stub.getMaxContext(
            MaxContextRequest.newBuilder()
                .setCtx(ctx())
                .setMaxTier(maxTier)
                .build(),
        )

    suspend fun reportModelError(modelId: String, errorMessage: String = ""): ReportModelErrorResponse =
        stub.reportModelError(
            ReportModelErrorRequest.newBuilder()
                .setCtx(ctx())
                .setModelId(modelId)
                .setErrorMessage(errorMessage)
                .build(),
        )

    suspend fun reportModelSuccess(
        modelId: String,
        durationSeconds: Double = 0.0,
        inputTokens: Long = 0,
        outputTokens: Long = 0,
    ) {
        stub.reportModelSuccess(
            ReportModelSuccessRequest.newBuilder()
                .setCtx(ctx())
                .setModelId(modelId)
                .setDurationS(durationSeconds)
                .setInputTokens(inputTokens)
                .setOutputTokens(outputTokens)
                .build(),
        )
    }

    suspend fun listModelErrors(): List<ModelErrorInfo> =
        stub.listModelErrors(
            ListModelErrorsRequest.newBuilder().setCtx(ctx()).build(),
        ).errorsList

    suspend fun listModelStats(): List<ModelStatInfo> =
        stub.listModelStats(
            ListModelStatsRequest.newBuilder().setCtx(ctx()).build(),
        ).statsList

    suspend fun resetModelError(modelId: String): Boolean =
        stub.resetModelError(
            ResetModelErrorRequest.newBuilder()
                .setCtx(ctx())
                .setModelId(modelId)
                .build(),
        ).reEnabled

    suspend fun testModel(modelId: String): TestModelResponse =
        stub.testModel(
            TestModelRequest.newBuilder()
                .setCtx(ctx())
                .setModelId(modelId)
                .build(),
        )

    suspend fun getRateLimits() =
        stub.getRateLimits(
            RateLimitsRequest.newBuilder().setCtx(ctx()).build(),
        ).queuesList

    suspend fun invalidateClientTier(clientId: String = "") {
        stub.invalidateClientTier(
            InvalidateClientTierRequest.newBuilder()
                .setCtx(ctx(clientId))
                .setClientId(clientId)
                .build(),
        )
    }
}
