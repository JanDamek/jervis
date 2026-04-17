package com.jervis.contracts.server

import com.jervis.contracts.server.ServerForegroundServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerForegroundService.
 */
public object ServerForegroundServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerForegroundServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val foregroundStartMethod: MethodDescriptor<ForegroundStartRequest, ForegroundResponse>
    @JvmStatic
    get() = ServerForegroundServiceGrpc.getForegroundStartMethod()

  public val foregroundEndMethod: MethodDescriptor<ForegroundEndRequest, ForegroundResponse>
    @JvmStatic
    get() = ServerForegroundServiceGrpc.getForegroundEndMethod()

  public val chatOnCloudMethod: MethodDescriptor<ChatOnCloudRequest, ForegroundResponse>
    @JvmStatic
    get() = ServerForegroundServiceGrpc.getChatOnCloudMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerForegroundService service as suspending coroutines.
   */
  @StubFor(ServerForegroundServiceGrpc::class)
  public class ServerForegroundServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerForegroundServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerForegroundServiceCoroutineStub = ServerForegroundServiceCoroutineStub(channel, callOptions)

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
    public suspend fun foregroundStart(request: ForegroundStartRequest, headers: Metadata = Metadata()): ForegroundResponse = unaryRpc(
      channel,
      ServerForegroundServiceGrpc.getForegroundStartMethod(),
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
    public suspend fun foregroundEnd(request: ForegroundEndRequest, headers: Metadata = Metadata()): ForegroundResponse = unaryRpc(
      channel,
      ServerForegroundServiceGrpc.getForegroundEndMethod(),
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
    public suspend fun chatOnCloud(request: ChatOnCloudRequest, headers: Metadata = Metadata()): ForegroundResponse = unaryRpc(
      channel,
      ServerForegroundServiceGrpc.getChatOnCloudMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerForegroundService service based on Kotlin coroutines.
   */
  public abstract class ServerForegroundServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerForegroundService.ForegroundStart.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun foregroundStart(request: ForegroundStartRequest): ForegroundResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerForegroundService.ForegroundStart is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerForegroundService.ForegroundEnd.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun foregroundEnd(request: ForegroundEndRequest): ForegroundResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerForegroundService.ForegroundEnd is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerForegroundService.ChatOnCloud.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun chatOnCloud(request: ChatOnCloudRequest): ForegroundResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerForegroundService.ChatOnCloud is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerForegroundServiceGrpc.getForegroundStartMethod(),
      implementation = ::foregroundStart
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerForegroundServiceGrpc.getForegroundEndMethod(),
      implementation = ::foregroundEnd
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerForegroundServiceGrpc.getChatOnCloudMethod(),
      implementation = ::chatOnCloud
    )).build()
  }
}
