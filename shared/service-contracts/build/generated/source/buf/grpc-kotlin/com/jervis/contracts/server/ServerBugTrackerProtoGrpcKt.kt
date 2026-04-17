package com.jervis.contracts.server

import com.jervis.contracts.server.ServerBugTrackerServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerBugTrackerService.
 */
public object ServerBugTrackerServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerBugTrackerServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val createIssueMethod: MethodDescriptor<CreateIssueRequest, IssueResponse>
    @JvmStatic
    get() = ServerBugTrackerServiceGrpc.getCreateIssueMethod()

  public val addIssueCommentMethod: MethodDescriptor<AddIssueCommentRequest, CommentResponse>
    @JvmStatic
    get() = ServerBugTrackerServiceGrpc.getAddIssueCommentMethod()

  public val updateIssueMethod: MethodDescriptor<UpdateIssueRequest, IssueResponse>
    @JvmStatic
    get() = ServerBugTrackerServiceGrpc.getUpdateIssueMethod()

  public val listIssuesMethod: MethodDescriptor<ListIssuesRequest, ListIssuesResponse>
    @JvmStatic
    get() = ServerBugTrackerServiceGrpc.getListIssuesMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerBugTrackerService service as suspending coroutines.
   */
  @StubFor(ServerBugTrackerServiceGrpc::class)
  public class ServerBugTrackerServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerBugTrackerServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerBugTrackerServiceCoroutineStub = ServerBugTrackerServiceCoroutineStub(channel, callOptions)

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
    public suspend fun createIssue(request: CreateIssueRequest, headers: Metadata = Metadata()): IssueResponse = unaryRpc(
      channel,
      ServerBugTrackerServiceGrpc.getCreateIssueMethod(),
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
    public suspend fun addIssueComment(request: AddIssueCommentRequest, headers: Metadata = Metadata()): CommentResponse = unaryRpc(
      channel,
      ServerBugTrackerServiceGrpc.getAddIssueCommentMethod(),
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
    public suspend fun updateIssue(request: UpdateIssueRequest, headers: Metadata = Metadata()): IssueResponse = unaryRpc(
      channel,
      ServerBugTrackerServiceGrpc.getUpdateIssueMethod(),
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
    public suspend fun listIssues(request: ListIssuesRequest, headers: Metadata = Metadata()): ListIssuesResponse = unaryRpc(
      channel,
      ServerBugTrackerServiceGrpc.getListIssuesMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerBugTrackerService service based on Kotlin coroutines.
   */
  public abstract class ServerBugTrackerServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerBugTrackerService.CreateIssue.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun createIssue(request: CreateIssueRequest): IssueResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerBugTrackerService.CreateIssue is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerBugTrackerService.AddIssueComment.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun addIssueComment(request: AddIssueCommentRequest): CommentResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerBugTrackerService.AddIssueComment is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerBugTrackerService.UpdateIssue.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun updateIssue(request: UpdateIssueRequest): IssueResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerBugTrackerService.UpdateIssue is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerBugTrackerService.ListIssues.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listIssues(request: ListIssuesRequest): ListIssuesResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerBugTrackerService.ListIssues is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerBugTrackerServiceGrpc.getCreateIssueMethod(),
      implementation = ::createIssue
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerBugTrackerServiceGrpc.getAddIssueCommentMethod(),
      implementation = ::addIssueComment
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerBugTrackerServiceGrpc.getUpdateIssueMethod(),
      implementation = ::updateIssue
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerBugTrackerServiceGrpc.getListIssuesMethod(),
      implementation = ::listIssues
    )).build()
  }
}
