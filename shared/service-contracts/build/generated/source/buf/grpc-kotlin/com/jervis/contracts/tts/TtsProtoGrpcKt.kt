package com.jervis.contracts.tts

import com.jervis.contracts.tts.TtsServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.tts.TtsService.
 */
public object TtsServiceGrpcKt {
  public const val SERVICE_NAME: String = TtsServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val speakMethod: MethodDescriptor<SpeakRequest, SpeakResponse>
    @JvmStatic
    get() = TtsServiceGrpc.getSpeakMethod()

  public val speakStreamMethod: MethodDescriptor<SpeakRequest, AudioChunk>
    @JvmStatic
    get() = TtsServiceGrpc.getSpeakStreamMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.tts.TtsService service as suspending coroutines.
   */
  @StubFor(TtsServiceGrpc::class)
  public class TtsServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<TtsServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): TtsServiceCoroutineStub = TtsServiceCoroutineStub(channel, callOptions)

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
    public suspend fun speak(request: SpeakRequest, headers: Metadata = Metadata()): SpeakResponse = unaryRpc(
      channel,
      TtsServiceGrpc.getSpeakMethod(),
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
    public fun speakStream(request: SpeakRequest, headers: Metadata = Metadata()): Flow<AudioChunk> = serverStreamingRpc(
      channel,
      TtsServiceGrpc.getSpeakStreamMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.tts.TtsService service based on Kotlin coroutines.
   */
  public abstract class TtsServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.tts.TtsService.Speak.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun speak(request: SpeakRequest): SpeakResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.tts.TtsService.Speak is unimplemented"))

    /**
     * Returns a [Flow] of responses to an RPC for jervis.tts.TtsService.SpeakStream.
     *
     * If creating or collecting the returned flow fails with a [StatusException], the RPC
     * will fail with the corresponding [io.grpc.Status].  If it fails with a
     * [java.util.concurrent.CancellationException], the RPC will fail with status `Status.CANCELLED`.  If creating
     * or collecting the returned flow fails for any other reason, the RPC will fail with
     * `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open fun speakStream(request: SpeakRequest): Flow<AudioChunk> = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.tts.TtsService.SpeakStream is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = TtsServiceGrpc.getSpeakMethod(),
      implementation = ::speak
    ))
      .addMethod(serverStreamingServerMethodDefinition(
      context = this.context,
      descriptor = TtsServiceGrpc.getSpeakStreamMethod(),
      implementation = ::speakStream
    )).build()
  }
}
