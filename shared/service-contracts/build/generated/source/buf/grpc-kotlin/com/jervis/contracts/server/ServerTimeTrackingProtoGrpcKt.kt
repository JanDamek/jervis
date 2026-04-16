package com.jervis.contracts.server

import com.jervis.contracts.server.ServerTimeTrackingServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerTimeTrackingService.
 */
public object ServerTimeTrackingServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerTimeTrackingServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val logTimeMethod: MethodDescriptor<LogTimeRequest, LogTimeResponse>
    @JvmStatic
    get() = ServerTimeTrackingServiceGrpc.getLogTimeMethod()

  public val getSummaryMethod: MethodDescriptor<GetSummaryRequest, GetSummaryResponse>
    @JvmStatic
    get() = ServerTimeTrackingServiceGrpc.getGetSummaryMethod()

  public val getCapacityMethod: MethodDescriptor<GetCapacityRequest, GetCapacityResponse>
    @JvmStatic
    get() = ServerTimeTrackingServiceGrpc.getGetCapacityMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerTimeTrackingService service as suspending coroutines.
   */
  @StubFor(ServerTimeTrackingServiceGrpc::class)
  public class ServerTimeTrackingServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerTimeTrackingServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerTimeTrackingServiceCoroutineStub = ServerTimeTrackingServiceCoroutineStub(channel, callOptions)

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
    public suspend fun logTime(request: LogTimeRequest, headers: Metadata = Metadata()): LogTimeResponse = unaryRpc(
      channel,
      ServerTimeTrackingServiceGrpc.getLogTimeMethod(),
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
    public suspend fun getSummary(request: GetSummaryRequest, headers: Metadata = Metadata()): GetSummaryResponse = unaryRpc(
      channel,
      ServerTimeTrackingServiceGrpc.getGetSummaryMethod(),
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
    public suspend fun getCapacity(request: GetCapacityRequest, headers: Metadata = Metadata()): GetCapacityResponse = unaryRpc(
      channel,
      ServerTimeTrackingServiceGrpc.getGetCapacityMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerTimeTrackingService service based on Kotlin coroutines.
   */
  public abstract class ServerTimeTrackingServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerTimeTrackingService.LogTime.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun logTime(request: LogTimeRequest): LogTimeResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTimeTrackingService.LogTime is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTimeTrackingService.GetSummary.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getSummary(request: GetSummaryRequest): GetSummaryResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTimeTrackingService.GetSummary is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTimeTrackingService.GetCapacity.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getCapacity(request: GetCapacityRequest): GetCapacityResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTimeTrackingService.GetCapacity is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTimeTrackingServiceGrpc.getLogTimeMethod(),
      implementation = ::logTime
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTimeTrackingServiceGrpc.getGetSummaryMethod(),
      implementation = ::getSummary
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTimeTrackingServiceGrpc.getGetCapacityMethod(),
      implementation = ::getCapacity
    )).build()
  }
}
