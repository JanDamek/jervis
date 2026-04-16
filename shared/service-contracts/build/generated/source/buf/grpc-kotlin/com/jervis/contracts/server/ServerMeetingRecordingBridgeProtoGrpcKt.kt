package com.jervis.contracts.server

import com.jervis.contracts.server.ServerMeetingRecordingBridgeServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerMeetingRecordingBridgeService.
 */
public object ServerMeetingRecordingBridgeServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerMeetingRecordingBridgeServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val startRecordingMethod: MethodDescriptor<StartRecordingRequest, StartRecordingResponse>
    @JvmStatic
    get() = ServerMeetingRecordingBridgeServiceGrpc.getStartRecordingMethod()

  public val uploadChunkMethod: MethodDescriptor<UploadChunkRequest, UploadChunkResponse>
    @JvmStatic
    get() = ServerMeetingRecordingBridgeServiceGrpc.getUploadChunkMethod()

  public val finalizeRecordingMethod:
      MethodDescriptor<FinalizeRecordingRequest, FinalizeRecordingResponse>
    @JvmStatic
    get() = ServerMeetingRecordingBridgeServiceGrpc.getFinalizeRecordingMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerMeetingRecordingBridgeService service as suspending coroutines.
   */
  @StubFor(ServerMeetingRecordingBridgeServiceGrpc::class)
  public class ServerMeetingRecordingBridgeServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerMeetingRecordingBridgeServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerMeetingRecordingBridgeServiceCoroutineStub = ServerMeetingRecordingBridgeServiceCoroutineStub(channel, callOptions)

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
    public suspend fun startRecording(request: StartRecordingRequest, headers: Metadata = Metadata()): StartRecordingResponse = unaryRpc(
      channel,
      ServerMeetingRecordingBridgeServiceGrpc.getStartRecordingMethod(),
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
    public suspend fun uploadChunk(request: UploadChunkRequest, headers: Metadata = Metadata()): UploadChunkResponse = unaryRpc(
      channel,
      ServerMeetingRecordingBridgeServiceGrpc.getUploadChunkMethod(),
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
    public suspend fun finalizeRecording(request: FinalizeRecordingRequest, headers: Metadata = Metadata()): FinalizeRecordingResponse = unaryRpc(
      channel,
      ServerMeetingRecordingBridgeServiceGrpc.getFinalizeRecordingMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerMeetingRecordingBridgeService service based on Kotlin coroutines.
   */
  public abstract class ServerMeetingRecordingBridgeServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerMeetingRecordingBridgeService.StartRecording.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun startRecording(request: StartRecordingRequest): StartRecordingResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerMeetingRecordingBridgeService.StartRecording is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerMeetingRecordingBridgeService.UploadChunk.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun uploadChunk(request: UploadChunkRequest): UploadChunkResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerMeetingRecordingBridgeService.UploadChunk is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerMeetingRecordingBridgeService.FinalizeRecording.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun finalizeRecording(request: FinalizeRecordingRequest): FinalizeRecordingResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerMeetingRecordingBridgeService.FinalizeRecording is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerMeetingRecordingBridgeServiceGrpc.getStartRecordingMethod(),
      implementation = ::startRecording
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerMeetingRecordingBridgeServiceGrpc.getUploadChunkMethod(),
      implementation = ::uploadChunk
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerMeetingRecordingBridgeServiceGrpc.getFinalizeRecordingMethod(),
      implementation = ::finalizeRecording
    )).build()
  }
}
