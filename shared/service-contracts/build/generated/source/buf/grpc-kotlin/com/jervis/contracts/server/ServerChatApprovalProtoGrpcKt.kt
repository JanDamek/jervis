package com.jervis.contracts.server

import com.jervis.contracts.server.ServerChatApprovalServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerChatApprovalService.
 */
public object ServerChatApprovalServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerChatApprovalServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val broadcastMethod: MethodDescriptor<ApprovalBroadcastRequest, ApprovalBroadcastResponse>
    @JvmStatic
    get() = ServerChatApprovalServiceGrpc.getBroadcastMethod()

  public val resolvedMethod: MethodDescriptor<ApprovalResolvedRequest, ApprovalResolvedResponse>
    @JvmStatic
    get() = ServerChatApprovalServiceGrpc.getResolvedMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerChatApprovalService service as suspending coroutines.
   */
  @StubFor(ServerChatApprovalServiceGrpc::class)
  public class ServerChatApprovalServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerChatApprovalServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerChatApprovalServiceCoroutineStub = ServerChatApprovalServiceCoroutineStub(channel, callOptions)

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
    public suspend fun broadcast(request: ApprovalBroadcastRequest, headers: Metadata = Metadata()): ApprovalBroadcastResponse = unaryRpc(
      channel,
      ServerChatApprovalServiceGrpc.getBroadcastMethod(),
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
    public suspend fun resolved(request: ApprovalResolvedRequest, headers: Metadata = Metadata()): ApprovalResolvedResponse = unaryRpc(
      channel,
      ServerChatApprovalServiceGrpc.getResolvedMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerChatApprovalService service based on Kotlin coroutines.
   */
  public abstract class ServerChatApprovalServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerChatApprovalService.Broadcast.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun broadcast(request: ApprovalBroadcastRequest): ApprovalBroadcastResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerChatApprovalService.Broadcast is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerChatApprovalService.Resolved.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun resolved(request: ApprovalResolvedRequest): ApprovalResolvedResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerChatApprovalService.Resolved is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerChatApprovalServiceGrpc.getBroadcastMethod(),
      implementation = ::broadcast
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerChatApprovalServiceGrpc.getResolvedMethod(),
      implementation = ::resolved
    )).build()
  }
}
