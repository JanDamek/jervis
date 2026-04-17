package com.jervis.contracts.server

import com.jervis.contracts.server.ServerChatContextServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerChatContextService.
 */
public object ServerChatContextServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerChatContextServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val listClientsProjectsMethod:
      MethodDescriptor<ClientsProjectsRequest, ClientsProjectsResponse>
    @JvmStatic
    get() = ServerChatContextServiceGrpc.getListClientsProjectsMethod()

  public val pendingUserTasksSummaryMethod:
      MethodDescriptor<PendingUserTasksRequest, PendingUserTasksResponse>
    @JvmStatic
    get() = ServerChatContextServiceGrpc.getPendingUserTasksSummaryMethod()

  public val unclassifiedMeetingsCountMethod:
      MethodDescriptor<UnclassifiedCountRequest, UnclassifiedCountResponse>
    @JvmStatic
    get() = ServerChatContextServiceGrpc.getUnclassifiedMeetingsCountMethod()

  public val getUserTimezoneMethod: MethodDescriptor<UserTimezoneRequest, UserTimezoneResponse>
    @JvmStatic
    get() = ServerChatContextServiceGrpc.getGetUserTimezoneMethod()

  public val getActiveChatTopicsMethod:
      MethodDescriptor<ActiveChatTopicsRequest, ActiveChatTopicsResponse>
    @JvmStatic
    get() = ServerChatContextServiceGrpc.getGetActiveChatTopicsMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerChatContextService service as suspending coroutines.
   */
  @StubFor(ServerChatContextServiceGrpc::class)
  public class ServerChatContextServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerChatContextServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerChatContextServiceCoroutineStub = ServerChatContextServiceCoroutineStub(channel, callOptions)

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
    public suspend fun listClientsProjects(request: ClientsProjectsRequest, headers: Metadata = Metadata()): ClientsProjectsResponse = unaryRpc(
      channel,
      ServerChatContextServiceGrpc.getListClientsProjectsMethod(),
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
    public suspend fun pendingUserTasksSummary(request: PendingUserTasksRequest, headers: Metadata = Metadata()): PendingUserTasksResponse = unaryRpc(
      channel,
      ServerChatContextServiceGrpc.getPendingUserTasksSummaryMethod(),
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
    public suspend fun unclassifiedMeetingsCount(request: UnclassifiedCountRequest, headers: Metadata = Metadata()): UnclassifiedCountResponse = unaryRpc(
      channel,
      ServerChatContextServiceGrpc.getUnclassifiedMeetingsCountMethod(),
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
    public suspend fun getUserTimezone(request: UserTimezoneRequest, headers: Metadata = Metadata()): UserTimezoneResponse = unaryRpc(
      channel,
      ServerChatContextServiceGrpc.getGetUserTimezoneMethod(),
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
    public suspend fun getActiveChatTopics(request: ActiveChatTopicsRequest, headers: Metadata = Metadata()): ActiveChatTopicsResponse = unaryRpc(
      channel,
      ServerChatContextServiceGrpc.getGetActiveChatTopicsMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerChatContextService service based on Kotlin coroutines.
   */
  public abstract class ServerChatContextServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerChatContextService.ListClientsProjects.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listClientsProjects(request: ClientsProjectsRequest): ClientsProjectsResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerChatContextService.ListClientsProjects is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerChatContextService.PendingUserTasksSummary.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun pendingUserTasksSummary(request: PendingUserTasksRequest): PendingUserTasksResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerChatContextService.PendingUserTasksSummary is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerChatContextService.UnclassifiedMeetingsCount.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun unclassifiedMeetingsCount(request: UnclassifiedCountRequest): UnclassifiedCountResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerChatContextService.UnclassifiedMeetingsCount is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerChatContextService.GetUserTimezone.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getUserTimezone(request: UserTimezoneRequest): UserTimezoneResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerChatContextService.GetUserTimezone is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerChatContextService.GetActiveChatTopics.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getActiveChatTopics(request: ActiveChatTopicsRequest): ActiveChatTopicsResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerChatContextService.GetActiveChatTopics is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerChatContextServiceGrpc.getListClientsProjectsMethod(),
      implementation = ::listClientsProjects
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerChatContextServiceGrpc.getPendingUserTasksSummaryMethod(),
      implementation = ::pendingUserTasksSummary
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerChatContextServiceGrpc.getUnclassifiedMeetingsCountMethod(),
      implementation = ::unclassifiedMeetingsCount
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerChatContextServiceGrpc.getGetUserTimezoneMethod(),
      implementation = ::getUserTimezone
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerChatContextServiceGrpc.getGetActiveChatTopicsMethod(),
      implementation = ::getActiveChatTopics
    )).build()
  }
}
