package com.jervis.infrastructure.grpc

import com.jervis.contracts.server.GpuIdleRequest
import com.jervis.contracts.server.GpuIdleResponse
import com.jervis.contracts.server.ServerGpuIdleServiceGrpcKt
import com.jervis.task.BackgroundEngine
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class ServerGpuIdleGrpcImpl(
    private val backgroundEngine: BackgroundEngine,
) : ServerGpuIdleServiceGrpcKt.ServerGpuIdleServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun gpuIdle(request: GpuIdleRequest): GpuIdleResponse =
        try {
            logger.info { "GPU_IDLE | idle_seconds=${request.idleSeconds} — triggering BackgroundEngine.onGpuIdle" }
            backgroundEngine.onGpuIdle()
            GpuIdleResponse.newBuilder().setOk(true).build()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to handle GPU idle notification" }
            GpuIdleResponse.newBuilder().setOk(false).setError(e.message.orEmpty()).build()
        }
}
