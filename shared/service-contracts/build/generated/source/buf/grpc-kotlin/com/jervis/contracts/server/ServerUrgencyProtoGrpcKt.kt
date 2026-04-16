package com.jervis.contracts.server

import com.jervis.contracts.server.ServerUrgencyServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerUrgencyService.
 */
public object ServerUrgencyServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerUrgencyServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val getConfigMethod: MethodDescriptor<GetUrgencyConfigRequest, UrgencyPayload>
    @JvmStatic
    get() = ServerUrgencyServiceGrpc.getGetConfigMethod()

  public val updateConfigMethod: MethodDescriptor<UpdateUrgencyConfigRequest, UrgencyPayload>
    @JvmStatic
    get() = ServerUrgencyServiceGrpc.getUpdateConfigMethod()

  public val getPresenceMethod: MethodDescriptor<GetUserPresenceRequest, UrgencyPayload>
    @JvmStatic
    get() = ServerUrgencyServiceGrpc.getGetPresenceMethod()

  public val bumpDeadlineMethod: MethodDescriptor<BumpDeadlineRequest, BumpDeadlineResponse>
    @JvmStatic
    get() = ServerUrgencyServiceGrpc.getBumpDeadlineMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerUrgencyService service as suspending coroutines.
   */
  @StubFor(ServerUrgencyServiceGrpc::class)
  public class ServerUrgencyServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerUrgencyServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerUrgencyServiceCoroutineStub = ServerUrgencyServiceCoroutineStub(channel, callOptions)

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
    public suspend fun getConfig(request: GetUrgencyConfigRequest, headers: Metadata = Metadata()): UrgencyPayload = unaryRpc(
      channel,
      ServerUrgencyServiceGrpc.getGetConfigMethod(),
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
    public suspend fun updateConfig(request: UpdateUrgencyConfigRequest, headers: Metadata = Metadata()): UrgencyPayload = unaryRpc(
      channel,
      ServerUrgencyServiceGrpc.getUpdateConfigMethod(),
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
    public suspend fun getPresence(request: GetUserPresenceRequest, headers: Metadata = Metadata()): UrgencyPayload = unaryRpc(
      channel,
      ServerUrgencyServiceGrpc.getGetPresenceMethod(),
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
    public suspend fun bumpDeadline(request: BumpDeadlineRequest, headers: Metadata = Metadata()): BumpDeadlineResponse = unaryRpc(
      channel,
      ServerUrgencyServiceGrpc.getBumpDeadlineMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerUrgencyService service based on Kotlin coroutines.
   */
  public abstract class ServerUrgencyServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerUrgencyService.GetConfig.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getConfig(request: GetUrgencyConfigRequest): UrgencyPayload = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerUrgencyService.GetConfig is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerUrgencyService.UpdateConfig.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun updateConfig(request: UpdateUrgencyConfigRequest): UrgencyPayload = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerUrgencyService.UpdateConfig is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerUrgencyService.GetPresence.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getPresence(request: GetUserPresenceRequest): UrgencyPayload = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerUrgencyService.GetPresence is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerUrgencyService.BumpDeadline.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun bumpDeadline(request: BumpDeadlineRequest): BumpDeadlineResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerUrgencyService.BumpDeadline is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerUrgencyServiceGrpc.getGetConfigMethod(),
      implementation = ::getConfig
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerUrgencyServiceGrpc.getUpdateConfigMethod(),
      implementation = ::updateConfig
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerUrgencyServiceGrpc.getGetPresenceMethod(),
      implementation = ::getPresence
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerUrgencyServiceGrpc.getBumpDeadlineMethod(),
      implementation = ::bumpDeadline
    )).build()
  }
}
