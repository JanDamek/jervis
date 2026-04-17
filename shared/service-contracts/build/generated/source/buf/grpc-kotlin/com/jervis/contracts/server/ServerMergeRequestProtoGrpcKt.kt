package com.jervis.contracts.server

import com.jervis.contracts.server.ServerMergeRequestServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerMergeRequestService.
 */
public object ServerMergeRequestServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerMergeRequestServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val resolveReviewLanguageMethod:
      MethodDescriptor<ResolveReviewLanguageRequest, ResolveReviewLanguageResponse>
    @JvmStatic
    get() = ServerMergeRequestServiceGrpc.getResolveReviewLanguageMethod()

  public val createMergeRequestMethod:
      MethodDescriptor<CreateMergeRequestRequest, CreateMergeRequestResponse>
    @JvmStatic
    get() = ServerMergeRequestServiceGrpc.getCreateMergeRequestMethod()

  public val getMergeRequestDiffMethod:
      MethodDescriptor<GetMergeRequestDiffRequest, GetMergeRequestDiffResponse>
    @JvmStatic
    get() = ServerMergeRequestServiceGrpc.getGetMergeRequestDiffMethod()

  public val postMrCommentMethod: MethodDescriptor<PostMrCommentRequest, PostMrCommentResponse>
    @JvmStatic
    get() = ServerMergeRequestServiceGrpc.getPostMrCommentMethod()

  public val postMrInlineCommentsMethod:
      MethodDescriptor<PostMrInlineCommentsRequest, PostMrInlineCommentsResponse>
    @JvmStatic
    get() = ServerMergeRequestServiceGrpc.getPostMrInlineCommentsMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerMergeRequestService service as suspending coroutines.
   */
  @StubFor(ServerMergeRequestServiceGrpc::class)
  public class ServerMergeRequestServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerMergeRequestServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerMergeRequestServiceCoroutineStub = ServerMergeRequestServiceCoroutineStub(channel, callOptions)

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
    public suspend fun resolveReviewLanguage(request: ResolveReviewLanguageRequest, headers: Metadata = Metadata()): ResolveReviewLanguageResponse = unaryRpc(
      channel,
      ServerMergeRequestServiceGrpc.getResolveReviewLanguageMethod(),
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
    public suspend fun createMergeRequest(request: CreateMergeRequestRequest, headers: Metadata = Metadata()): CreateMergeRequestResponse = unaryRpc(
      channel,
      ServerMergeRequestServiceGrpc.getCreateMergeRequestMethod(),
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
    public suspend fun getMergeRequestDiff(request: GetMergeRequestDiffRequest, headers: Metadata = Metadata()): GetMergeRequestDiffResponse = unaryRpc(
      channel,
      ServerMergeRequestServiceGrpc.getGetMergeRequestDiffMethod(),
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
    public suspend fun postMrComment(request: PostMrCommentRequest, headers: Metadata = Metadata()): PostMrCommentResponse = unaryRpc(
      channel,
      ServerMergeRequestServiceGrpc.getPostMrCommentMethod(),
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
    public suspend fun postMrInlineComments(request: PostMrInlineCommentsRequest, headers: Metadata = Metadata()): PostMrInlineCommentsResponse = unaryRpc(
      channel,
      ServerMergeRequestServiceGrpc.getPostMrInlineCommentsMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerMergeRequestService service based on Kotlin coroutines.
   */
  public abstract class ServerMergeRequestServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerMergeRequestService.ResolveReviewLanguage.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun resolveReviewLanguage(request: ResolveReviewLanguageRequest): ResolveReviewLanguageResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerMergeRequestService.ResolveReviewLanguage is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerMergeRequestService.CreateMergeRequest.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun createMergeRequest(request: CreateMergeRequestRequest): CreateMergeRequestResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerMergeRequestService.CreateMergeRequest is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerMergeRequestService.GetMergeRequestDiff.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getMergeRequestDiff(request: GetMergeRequestDiffRequest): GetMergeRequestDiffResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerMergeRequestService.GetMergeRequestDiff is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerMergeRequestService.PostMrComment.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun postMrComment(request: PostMrCommentRequest): PostMrCommentResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerMergeRequestService.PostMrComment is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerMergeRequestService.PostMrInlineComments.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun postMrInlineComments(request: PostMrInlineCommentsRequest): PostMrInlineCommentsResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerMergeRequestService.PostMrInlineComments is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerMergeRequestServiceGrpc.getResolveReviewLanguageMethod(),
      implementation = ::resolveReviewLanguage
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerMergeRequestServiceGrpc.getCreateMergeRequestMethod(),
      implementation = ::createMergeRequest
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerMergeRequestServiceGrpc.getGetMergeRequestDiffMethod(),
      implementation = ::getMergeRequestDiff
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerMergeRequestServiceGrpc.getPostMrCommentMethod(),
      implementation = ::postMrComment
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerMergeRequestServiceGrpc.getPostMrInlineCommentsMethod(),
      implementation = ::postMrInlineComments
    )).build()
  }
}
