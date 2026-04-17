package com.jervis.infrastructure.grpc

import com.jervis.contracts.server.ChatOnCloudRequest
import com.jervis.contracts.server.ForegroundEndRequest
import com.jervis.contracts.server.ForegroundResponse
import com.jervis.contracts.server.ForegroundStartRequest
import com.jervis.contracts.server.ServerForegroundServiceGrpcKt
import com.jervis.task.BackgroundEngine
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class ServerForegroundGrpcImpl(
    private val backgroundEngine: BackgroundEngine,
) : ServerForegroundServiceGrpcKt.ServerForegroundServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun foregroundStart(request: ForegroundStartRequest): ForegroundResponse =
        try {
            backgroundEngine.reserveGpuForChat()
            ForegroundResponse.newBuilder().setOk(true).build()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to reserve GPU for chat" }
            ForegroundResponse.newBuilder().setOk(false).setError(e.message.orEmpty()).build()
        }

    override suspend fun foregroundEnd(request: ForegroundEndRequest): ForegroundResponse =
        try {
            backgroundEngine.releaseGpuForChat()
            ForegroundResponse.newBuilder().setOk(true).build()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to release GPU for chat" }
            ForegroundResponse.newBuilder().setOk(false).setError(e.message.orEmpty()).build()
        }

    override suspend fun chatOnCloud(request: ChatOnCloudRequest): ForegroundResponse =
        try {
            backgroundEngine.reportChatOnCloud()
            ForegroundResponse.newBuilder().setOk(true).build()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to report chat-on-cloud" }
            ForegroundResponse.newBuilder().setOk(false).setError(e.message.orEmpty()).build()
        }
}
