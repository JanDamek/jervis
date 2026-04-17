package com.jervis.contracts.server

import com.jervis.contracts.server.ServerMeetingsServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerMeetingsService.
 */
public object ServerMeetingsServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerMeetingsServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val getTranscriptMethod: MethodDescriptor<GetTranscriptRequest, GetTranscriptResponse>
    @JvmStatic
    get() = ServerMeetingsServiceGrpc.getGetTranscriptMethod()

  public val listMeetingsMethod: MethodDescriptor<ListMeetingsRequest, ListMeetingsResponse>
    @JvmStatic
    get() = ServerMeetingsServiceGrpc.getListMeetingsMethod()

  public val listUnclassifiedMethod:
      MethodDescriptor<ListUnclassifiedRequest, ListUnclassifiedResponse>
    @JvmStatic
    get() = ServerMeetingsServiceGrpc.getListUnclassifiedMethod()

  public val classifyMeetingMethod:
      MethodDescriptor<ClassifyMeetingRequest, ClassifyMeetingResponse>
    @JvmStatic
    get() = ServerMeetingsServiceGrpc.getClassifyMeetingMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerMeetingsService service as suspending coroutines.
   */
  @StubFor(ServerMeetingsServiceGrpc::class)
  public class ServerMeetingsServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerMeetingsServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerMeetingsServiceCoroutineStub = ServerMeetingsServiceCoroutineStub(channel, callOptions)

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
    public suspend fun getTranscript(request: GetTranscriptRequest, headers: Metadata = Metadata()): GetTranscriptResponse = unaryRpc(
      channel,
      ServerMeetingsServiceGrpc.getGetTranscriptMethod(),
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
    public suspend fun listMeetings(request: ListMeetingsRequest, headers: Metadata = Metadata()): ListMeetingsResponse = unaryRpc(
      channel,
      ServerMeetingsServiceGrpc.getListMeetingsMethod(),
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
    public suspend fun listUnclassified(request: ListUnclassifiedRequest, headers: Metadata = Metadata()): ListUnclassifiedResponse = unaryRpc(
      channel,
      ServerMeetingsServiceGrpc.getListUnclassifiedMethod(),
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
    public suspend fun classifyMeeting(request: ClassifyMeetingRequest, headers: Metadata = Metadata()): ClassifyMeetingResponse = unaryRpc(
      channel,
      ServerMeetingsServiceGrpc.getClassifyMeetingMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerMeetingsService service based on Kotlin coroutines.
   */
  public abstract class ServerMeetingsServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerMeetingsService.GetTranscript.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getTranscript(request: GetTranscriptRequest): GetTranscriptResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerMeetingsService.GetTranscript is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerMeetingsService.ListMeetings.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listMeetings(request: ListMeetingsRequest): ListMeetingsResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerMeetingsService.ListMeetings is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerMeetingsService.ListUnclassified.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listUnclassified(request: ListUnclassifiedRequest): ListUnclassifiedResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerMeetingsService.ListUnclassified is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerMeetingsService.ClassifyMeeting.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun classifyMeeting(request: ClassifyMeetingRequest): ClassifyMeetingResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerMeetingsService.ClassifyMeeting is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerMeetingsServiceGrpc.getGetTranscriptMethod(),
      implementation = ::getTranscript
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerMeetingsServiceGrpc.getListMeetingsMethod(),
      implementation = ::listMeetings
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerMeetingsServiceGrpc.getListUnclassifiedMethod(),
      implementation = ::listUnclassified
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerMeetingsServiceGrpc.getClassifyMeetingMethod(),
      implementation = ::classifyMeeting
    )).build()
  }
}
