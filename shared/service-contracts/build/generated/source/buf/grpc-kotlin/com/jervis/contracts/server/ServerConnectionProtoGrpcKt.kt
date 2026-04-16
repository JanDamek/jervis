package com.jervis.contracts.server

import com.jervis.contracts.server.ServerConnectionServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerConnectionService.
 */
public object ServerConnectionServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerConnectionServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val approveReloginMethod: MethodDescriptor<ApproveReloginRequest, ApproveReloginResponse>
    @JvmStatic
    get() = ServerConnectionServiceGrpc.getApproveReloginMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerConnectionService service as suspending coroutines.
   */
  @StubFor(ServerConnectionServiceGrpc::class)
  public class ServerConnectionServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerConnectionServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerConnectionServiceCoroutineStub = ServerConnectionServiceCoroutineStub(channel, callOptions)

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
    public suspend fun approveRelogin(request: ApproveReloginRequest, headers: Metadata = Metadata()): ApproveReloginResponse = unaryRpc(
      channel,
      ServerConnectionServiceGrpc.getApproveReloginMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerConnectionService service based on Kotlin coroutines.
   */
  public abstract class ServerConnectionServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerConnectionService.ApproveRelogin.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun approveRelogin(request: ApproveReloginRequest): ApproveReloginResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerConnectionService.ApproveRelogin is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerConnectionServiceGrpc.getApproveReloginMethod(),
      implementation = ::approveRelogin
    )).build()
  }
}
