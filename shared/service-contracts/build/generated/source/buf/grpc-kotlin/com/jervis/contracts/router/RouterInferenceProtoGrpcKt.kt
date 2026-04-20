package com.jervis.contracts.router

import com.jervis.contracts.router.RouterInferenceServiceGrpc.getServiceDescriptor
import io.grpc.CallOptions
import io.grpc.CallOptions.DEFAULT
import io.grpc.Channel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerServiceDefinition
import io.grpc.ServerServiceDefinition.builder
import io.grpc.ServiceDescriptor
import io.grpc.Status.UNIMPLEMENTED
import io.grpc.StatusException
import io.grpc.kotlin.AbstractCoroutineServerImpl
import io.grpc.kotlin.AbstractCoroutineStub
import io.grpc.kotlin.ClientCalls.serverStreamingRpc
import io.grpc.kotlin.ClientCalls.unaryRpc
import io.grpc.kotlin.ServerCalls.serverStreamingServerMethodDefinition
import io.grpc.kotlin.ServerCalls.unaryServerMethodDefinition
import io.grpc.kotlin.StubFor
import kotlin.String
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlinx.coroutines.flow.Flow

/**
 * Holder for Kotlin coroutine-based client and server APIs for jervis.router.RouterInferenceService.
 */
public object RouterInferenceServiceGrpcKt {
  public const val SERVICE_NAME: String = RouterInferenceServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val chatMethod: MethodDescriptor<ChatRequest, ChatChunk>
    @JvmStatic
    get() = RouterInferenceServiceGrpc.getChatMethod()

  public val generateMethod: MethodDescriptor<GenerateRequest, GenerateChunk>
    @JvmStatic
    get() = RouterInferenceServiceGrpc.getGenerateMethod()

  public val embedMethod: MethodDescriptor<EmbedRequest, EmbedResponse>
    @JvmStatic
    get() = RouterInferenceServiceGrpc.getEmbedMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.router.RouterInferenceService service as suspending coroutines.
   */
  @StubFor(RouterInferenceServiceGrpc::class)
  public class RouterInferenceServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<RouterInferenceServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): RouterInferenceServiceCoroutineStub = RouterInferenceServiceCoroutineStub(channel, callOptions)

    /**
     * Returns a [Flow] that, when collected, executes this RPC and emits responses from the
     * server as they arrive.  That flow finishes normally if the server closes its response with
     * [`Status.OK`][io.grpc.Status], and fails by throwing a [StatusException] otherwise.  If
     * collecting the flow downstream fails exceptionally (including via cancellation), the RPC
     * is cancelled with that exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return A flow that, when collected, emits the responses from the server.
     */
    public fun chat(request: ChatRequest, headers: Metadata = Metadata()): Flow<ChatChunk> = serverStreamingRpc(
      channel,
      RouterInferenceServiceGrpc.getChatMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Returns a [Flow] that, when collected, executes this RPC and emits responses from the
     * server as they arrive.  That flow finishes normally if the server closes its response with
     * [`Status.OK`][io.grpc.Status], and fails by throwing a [StatusException] otherwise.  If
     * collecting the flow downstream fails exceptionally (including via cancellation), the RPC
     * is cancelled with that exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return A flow that, when collected, emits the responses from the server.
     */
    public fun generate(request: GenerateRequest, headers: Metadata = Metadata()): Flow<GenerateChunk> = serverStreamingRpc(
      channel,
      RouterInferenceServiceGrpc.getGenerateMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun embed(request: EmbedRequest, headers: Metadata = Metadata()): EmbedResponse = unaryRpc(
      channel,
      RouterInferenceServiceGrpc.getEmbedMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.router.RouterInferenceService service based on Kotlin coroutines.
   */
  public abstract class RouterInferenceServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns a [Flow] of responses to an RPC for jervis.router.RouterInferenceService.Chat.
     *
     * If creating or collecting the returned flow fails with a [StatusException], the RPC
     * will fail with the corresponding [io.grpc.Status].  If it fails with a
     * [java.util.concurrent.CancellationException], the RPC will fail with status `Status.CANCELLED`.  If creating
     * or collecting the returned flow fails for any other reason, the RPC will fail with
     * `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open fun chat(request: ChatRequest): Flow<ChatChunk> = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.router.RouterInferenceService.Chat is unimplemented"))

    /**
     * Returns a [Flow] of responses to an RPC for jervis.router.RouterInferenceService.Generate.
     *
     * If creating or collecting the returned flow fails with a [StatusException], the RPC
     * will fail with the corresponding [io.grpc.Status].  If it fails with a
     * [java.util.concurrent.CancellationException], the RPC will fail with status `Status.CANCELLED`.  If creating
     * or collecting the returned flow fails for any other reason, the RPC will fail with
     * `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open fun generate(request: GenerateRequest): Flow<GenerateChunk> = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.router.RouterInferenceService.Generate is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.router.RouterInferenceService.Embed.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun embed(request: EmbedRequest): EmbedResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.router.RouterInferenceService.Embed is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(serverStreamingServerMethodDefinition(
      context = this.context,
      descriptor = RouterInferenceServiceGrpc.getChatMethod(),
      implementation = ::chat
    ))
      .addMethod(serverStreamingServerMethodDefinition(
      context = this.context,
      descriptor = RouterInferenceServiceGrpc.getGenerateMethod(),
      implementation = ::generate
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = RouterInferenceServiceGrpc.getEmbedMethod(),
      implementation = ::embed
    )).build()
  }
}
