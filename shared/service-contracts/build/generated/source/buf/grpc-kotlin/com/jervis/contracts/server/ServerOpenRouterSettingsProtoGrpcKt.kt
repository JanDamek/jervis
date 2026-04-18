package com.jervis.contracts.server

import com.jervis.contracts.server.ServerOpenRouterSettingsServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for
 * jervis.server.ServerOpenRouterSettingsService.
 */
public object ServerOpenRouterSettingsServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerOpenRouterSettingsServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val getSettingsMethod: MethodDescriptor<GetOpenRouterSettingsRequest, OpenRouterSettings>
    @JvmStatic
    get() = ServerOpenRouterSettingsServiceGrpc.getGetSettingsMethod()

  public val persistModelStatsMethod:
      MethodDescriptor<PersistModelStatsRequest, PersistModelStatsResponse>
    @JvmStatic
    get() = ServerOpenRouterSettingsServiceGrpc.getPersistModelStatsMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerOpenRouterSettingsService service as
   * suspending coroutines.
   */
  @StubFor(ServerOpenRouterSettingsServiceGrpc::class)
  public class ServerOpenRouterSettingsServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerOpenRouterSettingsServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions):
        ServerOpenRouterSettingsServiceCoroutineStub =
        ServerOpenRouterSettingsServiceCoroutineStub(channel, callOptions)

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a
     * corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun getSettings(request: GetOpenRouterSettingsRequest, headers: Metadata =
        Metadata()): OpenRouterSettings = unaryRpc(
      channel,
      ServerOpenRouterSettingsServiceGrpc.getGetSettingsMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a
     * corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun persistModelStats(request: PersistModelStatsRequest, headers: Metadata =
        Metadata()): PersistModelStatsResponse = unaryRpc(
      channel,
      ServerOpenRouterSettingsServiceGrpc.getPersistModelStatsMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerOpenRouterSettingsService service based on
   * Kotlin coroutines.
   */
  public abstract class ServerOpenRouterSettingsServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerOpenRouterSettingsService.GetSettings.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getSettings(request: GetOpenRouterSettingsRequest): OpenRouterSettings =
        throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerOpenRouterSettingsService.GetSettings is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.server.ServerOpenRouterSettingsService.PersistModelStats.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun persistModelStats(request: PersistModelStatsRequest):
        PersistModelStatsResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerOpenRouterSettingsService.PersistModelStats is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerOpenRouterSettingsServiceGrpc.getGetSettingsMethod(),
      implementation = ::getSettings
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerOpenRouterSettingsServiceGrpc.getPersistModelStatsMethod(),
      implementation = ::persistModelStats
    )).build()
  }
}
