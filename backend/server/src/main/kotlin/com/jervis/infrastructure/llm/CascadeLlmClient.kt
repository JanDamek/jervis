package com.jervis.infrastructure.llm

import com.jervis.contracts.common.Capability
import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.router.ChatMessage
import com.jervis.contracts.router.ChatOptions
import com.jervis.contracts.router.ChatRequest
import com.jervis.contracts.router.RouterInferenceServiceGrpcKt
import com.jervis.infrastructure.grpc.GrpcChannels
import io.grpc.ManagedChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Server-only LLM client for short, chat-style prompts.
 *
 * Talks to `jervis.router.RouterInferenceService.Chat` on :5501 — the
 * router's REST surface is gone since 2026-04-21, every inference goes
 * through gRPC. Router derives routing from RequestContext:
 *   - scope.client_id → tier (NONE / T1 / T2)
 *   - capability      → bucket (chat / thinking / coding / visual / embedding)
 *   - deadline_iso    → urgency (realtime / urgent / normal / batch)
 *
 * Used for: merge project AI text resolution, TTS text normalization.
 * NOT for orchestrator or external callers.
 */
@Component
class CascadeLlmClient(
    @Qualifier(GrpcChannels.ROUTER_ADMIN_CHANNEL) channel: ManagedChannel,
) {
    private val stub = RouterInferenceServiceGrpcKt.RouterInferenceServiceCoroutineStub(channel)

    /**
     * Send a prompt via the router. Router picks model / local vs cloud.
     *
     * @param prompt user content
     * @param system optional system message
     * @param clientId tenant — router resolves tier from CloudModelPolicy
     * @param deadlineSeconds how many seconds from now the caller needs the
     *   answer. User-facing short prompts pass ~10-30 s, background jobs
     *   leave it null (router picks batch). The deadline is the sole
     *   urgency signal — no separate priority enum.
     */
    suspend fun prompt(
        prompt: String,
        system: String? = null,
        clientId: String? = null,
        deadlineSeconds: Long? = null,
    ): String? {
        val ctxBuilder = RequestContext.newBuilder()
            .setScope(Scope.newBuilder().setClientId(clientId.orEmpty()).build())
            .setCapability(Capability.CAPABILITY_CHAT)
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
        if (deadlineSeconds != null) {
            ctxBuilder.deadlineIso = DateTimeFormatter.ISO_INSTANT
                .format(Instant.now().plusSeconds(deadlineSeconds))
        }
        val ctx = ctxBuilder.build()

        val request = ChatRequest.newBuilder()
            .setCtx(ctx)
            .apply {
                if (!system.isNullOrBlank()) {
                    addMessages(ChatMessage.newBuilder().setRole("system").setContent(system).build())
                }
                addMessages(ChatMessage.newBuilder().setRole("user").setContent(prompt).build())
                options = ChatOptions.newBuilder().setTemperature(0.3).build()
            }
            .build()

        return try {
            val chunks = stub.chat(request).toList()
            val text = chunks.joinToString("") { it.contentDelta }
            text.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            // Parent coroutine cancelled — propagate, do NOT swallow. Otherwise
            // callers that expect cancellation to abort their own suspend chain
            // see a spurious null and keep executing.
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "CASCADE_LLM: failed: ${e.message}" }
            null
        }
    }
}
