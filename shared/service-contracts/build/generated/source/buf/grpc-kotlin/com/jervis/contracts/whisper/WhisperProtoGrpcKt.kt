package com.jervis.contracts.whisper

import com.jervis.contracts.whisper.WhisperServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.whisper.WhisperService.
 */
public object WhisperServiceGrpcKt {
  public const val SERVICE_NAME: String = WhisperServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val transcribeMethod: MethodDescriptor<TranscribeRequest, TranscribeEvent>
    @JvmStatic
    get() = WhisperServiceGrpc.getTranscribeMethod()

  public val healthMethod: MethodDescriptor<HealthRequest, HealthResponse>
    @JvmStatic
    get() = WhisperServiceGrpc.getHealthMethod()

  public val gpuReleaseMethod: MethodDescriptor<GpuReleaseRequest, GpuReleaseResponse>
    @JvmStatic
    get() = WhisperServiceGrpc.getGpuReleaseMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.whisper.WhisperService service as suspending coroutines.
   */
  @StubFor(WhisperServiceGrpc::class)
  public class WhisperServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<WhisperServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): WhisperServiceCoroutineStub = WhisperServiceCoroutineStub(channel, callOptions)

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
    public fun transcribe(request: TranscribeRequest, headers: Metadata = Metadata()): Flow<TranscribeEvent> = serverStreamingRpc(
      channel,
      WhisperServiceGrpc.getTranscribeMethod(),
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
    public suspend fun health(request: HealthRequest, headers: Metadata = Metadata()): HealthResponse = unaryRpc(
      channel,
      WhisperServiceGrpc.getHealthMethod(),
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
    public suspend fun gpuRelease(request: GpuReleaseRequest, headers: Metadata = Metadata()): GpuReleaseResponse = unaryRpc(
      channel,
      WhisperServiceGrpc.getGpuReleaseMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.whisper.WhisperService service based on Kotlin coroutines.
   */
  public abstract class WhisperServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns a [Flow] of responses to an RPC for jervis.whisper.WhisperService.Transcribe.
     *
     * If creating or collecting the returned flow fails with a [StatusException], the RPC
     * will fail with the corresponding [io.grpc.Status].  If it fails with a
     * [java.util.concurrent.CancellationException], the RPC will fail with status `Status.CANCELLED`.  If creating
     * or collecting the returned flow fails for any other reason, the RPC will fail with
     * `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open fun transcribe(request: TranscribeRequest): Flow<TranscribeEvent> = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.whisper.WhisperService.Transcribe is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.whisper.WhisperService.Health.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun health(request: HealthRequest): HealthResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.whisper.WhisperService.Health is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.whisper.WhisperService.GpuRelease.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun gpuRelease(request: GpuReleaseRequest): GpuReleaseResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.whisper.WhisperService.GpuRelease is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(serverStreamingServerMethodDefinition(
      context = this.context,
      descriptor = WhisperServiceGrpc.getTranscribeMethod(),
      implementation = ::transcribe
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = WhisperServiceGrpc.getHealthMethod(),
      implementation = ::health
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = WhisperServiceGrpc.getGpuReleaseMethod(),
      implementation = ::gpuRelease
    )).build()
  }
}
