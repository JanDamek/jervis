package com.jervis.contracts.visual_capture

import com.jervis.contracts.visual_capture.VisualCaptureServiceGrpc.getServiceDescriptor
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
 * jervis.visual_capture.VisualCaptureService.
 */
public object VisualCaptureServiceGrpcKt {
  public const val SERVICE_NAME: String = VisualCaptureServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val snapshotMethod: MethodDescriptor<SnapshotRequest, SnapshotResponse>
    @JvmStatic
    get() = VisualCaptureServiceGrpc.getSnapshotMethod()

  public val ptzGotoMethod: MethodDescriptor<PtzGotoRequest, PtzGotoResponse>
    @JvmStatic
    get() = VisualCaptureServiceGrpc.getPtzGotoMethod()

  public val ptzPresetsMethod: MethodDescriptor<PtzPresetsRequest, PtzPresetsResponse>
    @JvmStatic
    get() = VisualCaptureServiceGrpc.getPtzPresetsMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.visual_capture.VisualCaptureService service as
   * suspending coroutines.
   */
  @StubFor(VisualCaptureServiceGrpc::class)
  public class VisualCaptureServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<VisualCaptureServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions):
        VisualCaptureServiceCoroutineStub = VisualCaptureServiceCoroutineStub(channel, callOptions)

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
    public suspend fun snapshot(request: SnapshotRequest, headers: Metadata = Metadata()):
        SnapshotResponse = unaryRpc(
      channel,
      VisualCaptureServiceGrpc.getSnapshotMethod(),
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
    public suspend fun ptzGoto(request: PtzGotoRequest, headers: Metadata = Metadata()):
        PtzGotoResponse = unaryRpc(
      channel,
      VisualCaptureServiceGrpc.getPtzGotoMethod(),
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
    public suspend fun ptzPresets(request: PtzPresetsRequest, headers: Metadata = Metadata()):
        PtzPresetsResponse = unaryRpc(
      channel,
      VisualCaptureServiceGrpc.getPtzPresetsMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.visual_capture.VisualCaptureService service based on
   * Kotlin coroutines.
   */
  public abstract class VisualCaptureServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.visual_capture.VisualCaptureService.Snapshot.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun snapshot(request: SnapshotRequest): SnapshotResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.visual_capture.VisualCaptureService.Snapshot is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.visual_capture.VisualCaptureService.PtzGoto.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun ptzGoto(request: PtzGotoRequest): PtzGotoResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.visual_capture.VisualCaptureService.PtzGoto is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.visual_capture.VisualCaptureService.PtzPresets.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun ptzPresets(request: PtzPresetsRequest): PtzPresetsResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.visual_capture.VisualCaptureService.PtzPresets is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = VisualCaptureServiceGrpc.getSnapshotMethod(),
      implementation = ::snapshot
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = VisualCaptureServiceGrpc.getPtzGotoMethod(),
      implementation = ::ptzGoto
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = VisualCaptureServiceGrpc.getPtzPresetsMethod(),
      implementation = ::ptzPresets
    )).build()
  }
}
