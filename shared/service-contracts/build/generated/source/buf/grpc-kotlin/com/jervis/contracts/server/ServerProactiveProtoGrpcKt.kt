package com.jervis.contracts.server

import com.jervis.contracts.server.ServerProactiveServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerProactiveService.
 */
public object ServerProactiveServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerProactiveServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val morningBriefingMethod:
      MethodDescriptor<MorningBriefingRequest, MorningBriefingResponse>
    @JvmStatic
    get() = ServerProactiveServiceGrpc.getMorningBriefingMethod()

  public val overdueCheckMethod: MethodDescriptor<OverdueCheckRequest, OverdueCheckResponse>
    @JvmStatic
    get() = ServerProactiveServiceGrpc.getOverdueCheckMethod()

  public val weeklySummaryMethod: MethodDescriptor<WeeklySummaryRequest, WeeklySummaryResponse>
    @JvmStatic
    get() = ServerProactiveServiceGrpc.getWeeklySummaryMethod()

  public val vipAlertMethod: MethodDescriptor<VipAlertRequest, VipAlertResponse>
    @JvmStatic
    get() = ServerProactiveServiceGrpc.getVipAlertMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerProactiveService service as suspending coroutines.
   */
  @StubFor(ServerProactiveServiceGrpc::class)
  public class ServerProactiveServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerProactiveServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerProactiveServiceCoroutineStub = ServerProactiveServiceCoroutineStub(channel, callOptions)

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
    public suspend fun morningBriefing(request: MorningBriefingRequest, headers: Metadata = Metadata()): MorningBriefingResponse = unaryRpc(
      channel,
      ServerProactiveServiceGrpc.getMorningBriefingMethod(),
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
    public suspend fun overdueCheck(request: OverdueCheckRequest, headers: Metadata = Metadata()): OverdueCheckResponse = unaryRpc(
      channel,
      ServerProactiveServiceGrpc.getOverdueCheckMethod(),
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
    public suspend fun weeklySummary(request: WeeklySummaryRequest, headers: Metadata = Metadata()): WeeklySummaryResponse = unaryRpc(
      channel,
      ServerProactiveServiceGrpc.getWeeklySummaryMethod(),
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
    public suspend fun vipAlert(request: VipAlertRequest, headers: Metadata = Metadata()): VipAlertResponse = unaryRpc(
      channel,
      ServerProactiveServiceGrpc.getVipAlertMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerProactiveService service based on Kotlin coroutines.
   */
  public abstract class ServerProactiveServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerProactiveService.MorningBriefing.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun morningBriefing(request: MorningBriefingRequest): MorningBriefingResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerProactiveService.MorningBriefing is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerProactiveService.OverdueCheck.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun overdueCheck(request: OverdueCheckRequest): OverdueCheckResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerProactiveService.OverdueCheck is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerProactiveService.WeeklySummary.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun weeklySummary(request: WeeklySummaryRequest): WeeklySummaryResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerProactiveService.WeeklySummary is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerProactiveService.VipAlert.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun vipAlert(request: VipAlertRequest): VipAlertResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerProactiveService.VipAlert is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerProactiveServiceGrpc.getMorningBriefingMethod(),
      implementation = ::morningBriefing
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerProactiveServiceGrpc.getOverdueCheckMethod(),
      implementation = ::overdueCheck
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerProactiveServiceGrpc.getWeeklySummaryMethod(),
      implementation = ::weeklySummary
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerProactiveServiceGrpc.getVipAlertMethod(),
      implementation = ::vipAlert
    )).build()
  }
}
