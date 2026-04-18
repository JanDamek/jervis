package com.jervis.contracts.orchestrator

import com.jervis.contracts.orchestrator.OrchestratorDispatchServiceGrpc.getServiceDescriptor
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
import io.grpc.kotlin.ClientCalls.unaryRpc
import io.grpc.kotlin.ServerCalls.unaryServerMethodDefinition
import io.grpc.kotlin.StubFor
import kotlin.String
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * Holder for Kotlin coroutine-based client and server APIs for
 * jervis.orchestrator.OrchestratorDispatchService.
 */
public object OrchestratorDispatchServiceGrpcKt {
  public const val SERVICE_NAME: String = OrchestratorDispatchServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val qualifyMethod: MethodDescriptor<QualifyRequest, DispatchAck>
    @JvmStatic
    get() = OrchestratorDispatchServiceGrpc.getQualifyMethod()

  public val orchestrateMethod: MethodDescriptor<OrchestrateRequest, DispatchAck>
    @JvmStatic
    get() = OrchestratorDispatchServiceGrpc.getOrchestrateMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.orchestrator.OrchestratorDispatchService service as
   * suspending coroutines.
   */
  @StubFor(OrchestratorDispatchServiceGrpc::class)
  public class OrchestratorDispatchServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<OrchestratorDispatchServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions):
        OrchestratorDispatchServiceCoroutineStub = OrchestratorDispatchServiceCoroutineStub(channel,
        callOptions)

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a
     * corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun qualify(request: QualifyRequest, headers: Metadata = Metadata()): DispatchAck
        = unaryRpc(
      channel,
      OrchestratorDispatchServiceGrpc.getQualifyMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a
     * corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun orchestrate(request: OrchestrateRequest, headers: Metadata = Metadata()):
        DispatchAck = unaryRpc(
      channel,
      OrchestratorDispatchServiceGrpc.getOrchestrateMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.orchestrator.OrchestratorDispatchService service based on
   * Kotlin coroutines.
   */
  public abstract class OrchestratorDispatchServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.orchestrator.OrchestratorDispatchService.Qualify.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun qualify(request: QualifyRequest): DispatchAck = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorDispatchService.Qualify is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.orchestrator.OrchestratorDispatchService.Orchestrate.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun orchestrate(request: OrchestrateRequest): DispatchAck = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorDispatchService.Orchestrate is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorDispatchServiceGrpc.getQualifyMethod(),
      implementation = ::qualify
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorDispatchServiceGrpc.getOrchestrateMethod(),
      implementation = ::orchestrate
    )).build()
  }
}
