package com.jervis.git.rpc

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.orchestrator.JobLogsRequest
import com.jervis.contracts.orchestrator.OrchestratorJobLogsServiceGrpcKt
import com.jervis.dto.git.JobLogEventDto
import com.jervis.infrastructure.grpc.GrpcChannels
import com.jervis.service.meeting.IJobLogsService
import io.grpc.ManagedChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * JobLogsRpcImpl — kRPC relay for live K8s Job log streaming.
 *
 * Dials OrchestratorJobLogsService.StreamLogs (server-streaming gRPC) and
 * relays JobLogEvent entries to the UI via kRPC Flow.
 */
@Component
class JobLogsRpcImpl(
    @Qualifier(GrpcChannels.ORCHESTRATOR_CHANNEL) channel: ManagedChannel,
) : IJobLogsService {
    private val logger = KotlinLogging.logger {}
    private val stub = OrchestratorJobLogsServiceGrpcKt
        .OrchestratorJobLogsServiceCoroutineStub(channel)

    private fun ctx(): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    override fun subscribeToJobLogs(taskId: String): Flow<JobLogEventDto> = flow {
        logger.info { "JOB_LOGS_SUBSCRIBE | taskId=$taskId" }
        stub.streamLogs(
            JobLogsRequest.newBuilder()
                .setCtx(ctx())
                .setTaskId(taskId)
                .build(),
        )
            .map { event -> JobLogEventDto(type = event.type, content = event.content, tool = event.tool) }
            .catch { e ->
                logger.error(e) { "JOB_LOGS_ERROR | taskId=$taskId" }
                emit(JobLogEventDto(type = "error", content = "Failed to stream logs: ${e.message}"))
            }
            .collect { emit(it) }
    }
}
