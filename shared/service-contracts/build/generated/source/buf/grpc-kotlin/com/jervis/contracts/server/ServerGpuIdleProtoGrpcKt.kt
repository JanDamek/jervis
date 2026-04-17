package com.jervis.contracts.server

import com.jervis.contracts.server.ServerGpuIdleServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerGpuIdleService.
 */
public object ServerGpuIdleServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerGpuIdleServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val gpuIdleMethod: MethodDescriptor<GpuIdleRequest, GpuIdleResponse>
    @JvmStatic
    get() = ServerGpuIdleServiceGrpc.getGpuIdleMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerGpuIdleService service as suspending coroutines.
   */
  @StubFor(ServerGpuIdleServiceGrpc::class)
  public class ServerGpuIdleServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerGpuIdleServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerGpuIdleServiceCoroutineStub = ServerGpuIdleServiceCoroutineStub(channel, callOptions)

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
    public suspend fun gpuIdle(request: GpuIdleRequest, headers: Metadata = Metadata()): GpuIdleResponse = unaryRpc(
      channel,
      ServerGpuIdleServiceGrpc.getGpuIdleMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerGpuIdleService service based on Kotlin coroutines.
   */
  public abstract class ServerGpuIdleServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerGpuIdleService.GpuIdle.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun gpuIdle(request: GpuIdleRequest): GpuIdleResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerGpuIdleService.GpuIdle is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerGpuIdleServiceGrpc.getGpuIdleMethod(),
      implementation = ::gpuIdle
    )).build()
  }
}
