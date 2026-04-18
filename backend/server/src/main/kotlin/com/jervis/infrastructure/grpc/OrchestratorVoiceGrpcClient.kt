package com.jervis.infrastructure.grpc

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.orchestrator.OrchestratorVoiceServiceGrpcKt
import com.jervis.contracts.orchestrator.VoiceHintRequest
import com.jervis.contracts.orchestrator.VoiceHintResponse
import com.jervis.contracts.orchestrator.VoiceProcessRequest
import com.jervis.contracts.orchestrator.VoiceStreamEvent
import io.grpc.ManagedChannel
import kotlinx.coroutines.flow.Flow
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

// Kotlin-side client for jervis.orchestrator.OrchestratorVoiceService.
// Wraps Process (server-streaming) + Hint (unary). The FastAPI voice
// routes still run in parallel during the migration window; inline
// call sites in rpc/VoiceChatRouting.kt + voice/VoiceWebSocketHandler.kt
// will flip to this client in the follow-up slice that deletes the
// REST shim.
@Component
class OrchestratorVoiceGrpcClient(
    @Qualifier(GrpcChannels.ORCHESTRATOR_CHANNEL) channel: ManagedChannel,
) {
    private val stub = OrchestratorVoiceServiceGrpcKt.OrchestratorVoiceServiceCoroutineStub(channel)

    private fun ctx(clientId: String = ""): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().setClientId(clientId).build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    fun process(
        text: String,
        source: String = "app_chat",
        clientId: String? = null,
        projectId: String? = null,
        groupId: String? = null,
        tts: Boolean = true,
        meetingId: String? = null,
        liveAssist: Boolean = false,
        chunkIndex: Int = 0,
        isFinal: Boolean = true,
    ): Flow<VoiceStreamEvent> =
        stub.process(
            VoiceProcessRequest.newBuilder()
                .setCtx(ctx(clientId ?: ""))
                .setText(text)
                .setSource(source)
                .setClientId(clientId ?: "")
                .setProjectId(projectId ?: "")
                .setGroupId(groupId ?: "")
                .setTts(tts)
                .setMeetingId(meetingId ?: "")
                .setLiveAssist(liveAssist)
                .setChunkIndex(chunkIndex)
                .setIsFinal(isFinal)
                .build(),
        )

    suspend fun hint(text: String, clientId: String = "", projectId: String = ""): VoiceHintResponse =
        stub.hint(
            VoiceHintRequest.newBuilder()
                .setCtx(ctx(clientId))
                .setText(text)
                .setClientId(clientId)
                .setProjectId(projectId)
                .build(),
        )
}
