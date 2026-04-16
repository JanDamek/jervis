package com.jervis.contracts.server

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
import com.jervis.contracts.server.ServerO365DiscoveredResourcesServiceGrpc.getServiceDescriptor as serverO365DiscoveredResourcesServiceGrpcGetServiceDescriptor
import com.jervis.contracts.server.ServerUserActivityServiceGrpc.getServiceDescriptor as serverUserActivityServiceGrpcGetServiceDescriptor

/**
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerO365DiscoveredResourcesService.
 */
public object ServerO365DiscoveredResourcesServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerO365DiscoveredResourcesServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = serverO365DiscoveredResourcesServiceGrpcGetServiceDescriptor()

  public val listDiscoveredMethod: MethodDescriptor<ListDiscoveredRequest, ListDiscoveredResponse>
    @JvmStatic
    get() = ServerO365DiscoveredResourcesServiceGrpc.getListDiscoveredMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerO365DiscoveredResourcesService service as suspending coroutines.
   */
  @StubFor(ServerO365DiscoveredResourcesServiceGrpc::class)
  public class ServerO365DiscoveredResourcesServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerO365DiscoveredResourcesServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerO365DiscoveredResourcesServiceCoroutineStub = ServerO365DiscoveredResourcesServiceCoroutineStub(channel, callOptions)

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
    public suspend fun listDiscovered(request: ListDiscoveredRequest, headers: Metadata = Metadata()): ListDiscoveredResponse = unaryRpc(
      channel,
      ServerO365DiscoveredResourcesServiceGrpc.getListDiscoveredMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerO365DiscoveredResourcesService service based on Kotlin coroutines.
   */
  public abstract class ServerO365DiscoveredResourcesServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerO365DiscoveredResourcesService.ListDiscovered.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listDiscovered(request: ListDiscoveredRequest): ListDiscoveredResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerO365DiscoveredResourcesService.ListDiscovered is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(serverO365DiscoveredResourcesServiceGrpcGetServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerO365DiscoveredResourcesServiceGrpc.getListDiscoveredMethod(),
      implementation = ::listDiscovered
    )).build()
  }
}

/**
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerUserActivityService.
 */
public object ServerUserActivityServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerUserActivityServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = serverUserActivityServiceGrpcGetServiceDescriptor()

  public val lastActivityMethod: MethodDescriptor<LastActivityRequest, LastActivityResponse>
    @JvmStatic
    get() = ServerUserActivityServiceGrpc.getLastActivityMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerUserActivityService service as suspending coroutines.
   */
  @StubFor(ServerUserActivityServiceGrpc::class)
  public class ServerUserActivityServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerUserActivityServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerUserActivityServiceCoroutineStub = ServerUserActivityServiceCoroutineStub(channel, callOptions)

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
    public suspend fun lastActivity(request: LastActivityRequest, headers: Metadata = Metadata()): LastActivityResponse = unaryRpc(
      channel,
      ServerUserActivityServiceGrpc.getLastActivityMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerUserActivityService service based on Kotlin coroutines.
   */
  public abstract class ServerUserActivityServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerUserActivityService.LastActivity.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun lastActivity(request: LastActivityRequest): LastActivityResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerUserActivityService.LastActivity is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(serverUserActivityServiceGrpcGetServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerUserActivityServiceGrpc.getLastActivityMethod(),
      implementation = ::lastActivity
    )).build()
  }
}
