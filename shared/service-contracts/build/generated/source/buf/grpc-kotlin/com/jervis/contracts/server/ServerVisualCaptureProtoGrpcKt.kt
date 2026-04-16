package com.jervis.contracts.server

import com.jervis.contracts.server.ServerVisualCaptureServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerVisualCaptureService.
 */
public object ServerVisualCaptureServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerVisualCaptureServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val postResultMethod: MethodDescriptor<VisualResultRequest, VisualResultResponse>
    @JvmStatic
    get() = ServerVisualCaptureServiceGrpc.getPostResultMethod()

  public val snapshotMethod: MethodDescriptor<SnapshotRequest, RawJsonResponse>
    @JvmStatic
    get() = ServerVisualCaptureServiceGrpc.getSnapshotMethod()

  public val ptzMethod: MethodDescriptor<PtzRequest, RawJsonResponse>
    @JvmStatic
    get() = ServerVisualCaptureServiceGrpc.getPtzMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerVisualCaptureService service as suspending coroutines.
   */
  @StubFor(ServerVisualCaptureServiceGrpc::class)
  public class ServerVisualCaptureServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerVisualCaptureServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerVisualCaptureServiceCoroutineStub = ServerVisualCaptureServiceCoroutineStub(channel, callOptions)

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
    public suspend fun postResult(request: VisualResultRequest, headers: Metadata = Metadata()): VisualResultResponse = unaryRpc(
      channel,
      ServerVisualCaptureServiceGrpc.getPostResultMethod(),
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
    public suspend fun snapshot(request: SnapshotRequest, headers: Metadata = Metadata()): RawJsonResponse = unaryRpc(
      channel,
      ServerVisualCaptureServiceGrpc.getSnapshotMethod(),
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
    public suspend fun ptz(request: PtzRequest, headers: Metadata = Metadata()): RawJsonResponse = unaryRpc(
      channel,
      ServerVisualCaptureServiceGrpc.getPtzMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerVisualCaptureService service based on Kotlin coroutines.
   */
  public abstract class ServerVisualCaptureServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerVisualCaptureService.PostResult.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun postResult(request: VisualResultRequest): VisualResultResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerVisualCaptureService.PostResult is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerVisualCaptureService.Snapshot.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun snapshot(request: SnapshotRequest): RawJsonResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerVisualCaptureService.Snapshot is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerVisualCaptureService.Ptz.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun ptz(request: PtzRequest): RawJsonResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerVisualCaptureService.Ptz is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerVisualCaptureServiceGrpc.getPostResultMethod(),
      implementation = ::postResult
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerVisualCaptureServiceGrpc.getSnapshotMethod(),
      implementation = ::snapshot
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerVisualCaptureServiceGrpc.getPtzMethod(),
      implementation = ::ptz
    )).build()
  }
}
