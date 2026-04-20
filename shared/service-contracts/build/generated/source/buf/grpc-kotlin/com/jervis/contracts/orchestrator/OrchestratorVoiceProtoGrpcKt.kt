package com.jervis.contracts.orchestrator

import com.jervis.contracts.orchestrator.OrchestratorVoiceServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.orchestrator.OrchestratorVoiceService.
 */
public object OrchestratorVoiceServiceGrpcKt {
  public const val SERVICE_NAME: String = OrchestratorVoiceServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val processMethod: MethodDescriptor<VoiceProcessRequest, VoiceStreamEvent>
    @JvmStatic
    get() = OrchestratorVoiceServiceGrpc.getProcessMethod()

  public val hintMethod: MethodDescriptor<VoiceHintRequest, VoiceHintResponse>
    @JvmStatic
    get() = OrchestratorVoiceServiceGrpc.getHintMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.orchestrator.OrchestratorVoiceService service as suspending coroutines.
   */
  @StubFor(OrchestratorVoiceServiceGrpc::class)
  public class OrchestratorVoiceServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<OrchestratorVoiceServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): OrchestratorVoiceServiceCoroutineStub = OrchestratorVoiceServiceCoroutineStub(channel, callOptions)

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
    public fun process(request: VoiceProcessRequest, headers: Metadata = Metadata()): Flow<VoiceStreamEvent> = serverStreamingRpc(
      channel,
      OrchestratorVoiceServiceGrpc.getProcessMethod(),
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
    public suspend fun hint(request: VoiceHintRequest, headers: Metadata = Metadata()): VoiceHintResponse = unaryRpc(
      channel,
      OrchestratorVoiceServiceGrpc.getHintMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.orchestrator.OrchestratorVoiceService service based on Kotlin coroutines.
   */
  public abstract class OrchestratorVoiceServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns a [Flow] of responses to an RPC for jervis.orchestrator.OrchestratorVoiceService.Process.
     *
     * If creating or collecting the returned flow fails with a [StatusException], the RPC
     * will fail with the corresponding [io.grpc.Status].  If it fails with a
     * [java.util.concurrent.CancellationException], the RPC will fail with status `Status.CANCELLED`.  If creating
     * or collecting the returned flow fails for any other reason, the RPC will fail with
     * `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open fun process(request: VoiceProcessRequest): Flow<VoiceStreamEvent> = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorVoiceService.Process is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.orchestrator.OrchestratorVoiceService.Hint.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun hint(request: VoiceHintRequest): VoiceHintResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorVoiceService.Hint is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(serverStreamingServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorVoiceServiceGrpc.getProcessMethod(),
      implementation = ::process
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorVoiceServiceGrpc.getHintMethod(),
      implementation = ::hint
    )).build()
  }
}
