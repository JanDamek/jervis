package com.jervis.contracts.orchestrator

import com.jervis.contracts.orchestrator.OrchestratorMeetingHelperServiceGrpc.getServiceDescriptor
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
 * jervis.orchestrator.OrchestratorMeetingHelperService.
 */
public object OrchestratorMeetingHelperServiceGrpcKt {
  public const val SERVICE_NAME: String = OrchestratorMeetingHelperServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val startMethod: MethodDescriptor<StartHelperRequest, StartHelperResponse>
    @JvmStatic
    get() = OrchestratorMeetingHelperServiceGrpc.getStartMethod()

  public val stopMethod: MethodDescriptor<StopHelperRequest, StopHelperResponse>
    @JvmStatic
    get() = OrchestratorMeetingHelperServiceGrpc.getStopMethod()

  public val chunkMethod: MethodDescriptor<HelperChunkRequest, HelperChunkResponse>
    @JvmStatic
    get() = OrchestratorMeetingHelperServiceGrpc.getChunkMethod()

  public val statusMethod: MethodDescriptor<HelperStatusRequest, HelperStatusResponse>
    @JvmStatic
    get() = OrchestratorMeetingHelperServiceGrpc.getStatusMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.orchestrator.OrchestratorMeetingHelperService service as
   * suspending coroutines.
   */
  @StubFor(OrchestratorMeetingHelperServiceGrpc::class)
  public class OrchestratorMeetingHelperServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<OrchestratorMeetingHelperServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions):
        OrchestratorMeetingHelperServiceCoroutineStub =
        OrchestratorMeetingHelperServiceCoroutineStub(channel, callOptions)

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
    public suspend fun start(request: StartHelperRequest, headers: Metadata = Metadata()):
        StartHelperResponse = unaryRpc(
      channel,
      OrchestratorMeetingHelperServiceGrpc.getStartMethod(),
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
    public suspend fun stop(request: StopHelperRequest, headers: Metadata = Metadata()):
        StopHelperResponse = unaryRpc(
      channel,
      OrchestratorMeetingHelperServiceGrpc.getStopMethod(),
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
    public suspend fun chunk(request: HelperChunkRequest, headers: Metadata = Metadata()):
        HelperChunkResponse = unaryRpc(
      channel,
      OrchestratorMeetingHelperServiceGrpc.getChunkMethod(),
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
    public suspend fun status(request: HelperStatusRequest, headers: Metadata = Metadata()):
        HelperStatusResponse = unaryRpc(
      channel,
      OrchestratorMeetingHelperServiceGrpc.getStatusMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.orchestrator.OrchestratorMeetingHelperService service
   * based on Kotlin coroutines.
   */
  public abstract class OrchestratorMeetingHelperServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for
     * jervis.orchestrator.OrchestratorMeetingHelperService.Start.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun start(request: StartHelperRequest): StartHelperResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorMeetingHelperService.Start is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.orchestrator.OrchestratorMeetingHelperService.Stop.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun stop(request: StopHelperRequest): StopHelperResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorMeetingHelperService.Stop is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.orchestrator.OrchestratorMeetingHelperService.Chunk.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun chunk(request: HelperChunkRequest): HelperChunkResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorMeetingHelperService.Chunk is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.orchestrator.OrchestratorMeetingHelperService.Status.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun status(request: HelperStatusRequest): HelperStatusResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorMeetingHelperService.Status is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorMeetingHelperServiceGrpc.getStartMethod(),
      implementation = ::start
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorMeetingHelperServiceGrpc.getStopMethod(),
      implementation = ::stop
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorMeetingHelperServiceGrpc.getChunkMethod(),
      implementation = ::chunk
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorMeetingHelperServiceGrpc.getStatusMethod(),
      implementation = ::status
    )).build()
  }
}
