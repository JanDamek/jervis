package com.jervis.contracts.router

import com.jervis.contracts.router.RouterAdminServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.router.RouterAdminService.
 */
public object RouterAdminServiceGrpcKt {
  public const val SERVICE_NAME: String = RouterAdminServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val getMaxContextMethod: MethodDescriptor<MaxContextRequest, MaxContextResponse>
    @JvmStatic
    get() = RouterAdminServiceGrpc.getGetMaxContextMethod()

  public val reportModelErrorMethod:
      MethodDescriptor<ReportModelErrorRequest, ReportModelErrorResponse>
    @JvmStatic
    get() = RouterAdminServiceGrpc.getReportModelErrorMethod()

  public val reportModelSuccessMethod:
      MethodDescriptor<ReportModelSuccessRequest, ReportModelSuccessResponse>
    @JvmStatic
    get() = RouterAdminServiceGrpc.getReportModelSuccessMethod()

  public val listModelErrorsMethod:
      MethodDescriptor<ListModelErrorsRequest, ListModelErrorsResponse>
    @JvmStatic
    get() = RouterAdminServiceGrpc.getListModelErrorsMethod()

  public val listModelStatsMethod: MethodDescriptor<ListModelStatsRequest, ListModelStatsResponse>
    @JvmStatic
    get() = RouterAdminServiceGrpc.getListModelStatsMethod()

  public val resetModelErrorMethod:
      MethodDescriptor<ResetModelErrorRequest, ResetModelErrorResponse>
    @JvmStatic
    get() = RouterAdminServiceGrpc.getResetModelErrorMethod()

  public val testModelMethod: MethodDescriptor<TestModelRequest, TestModelResponse>
    @JvmStatic
    get() = RouterAdminServiceGrpc.getTestModelMethod()

  public val getRateLimitsMethod: MethodDescriptor<RateLimitsRequest, RateLimitsResponse>
    @JvmStatic
    get() = RouterAdminServiceGrpc.getGetRateLimitsMethod()

  public val invalidateClientTierMethod:
      MethodDescriptor<InvalidateClientTierRequest, InvalidateClientTierResponse>
    @JvmStatic
    get() = RouterAdminServiceGrpc.getInvalidateClientTierMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.router.RouterAdminService service as suspending coroutines.
   */
  @StubFor(RouterAdminServiceGrpc::class)
  public class RouterAdminServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<RouterAdminServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): RouterAdminServiceCoroutineStub = RouterAdminServiceCoroutineStub(channel, callOptions)

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
    public suspend fun getMaxContext(request: MaxContextRequest, headers: Metadata = Metadata()): MaxContextResponse = unaryRpc(
      channel,
      RouterAdminServiceGrpc.getGetMaxContextMethod(),
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
    public suspend fun reportModelError(request: ReportModelErrorRequest, headers: Metadata = Metadata()): ReportModelErrorResponse = unaryRpc(
      channel,
      RouterAdminServiceGrpc.getReportModelErrorMethod(),
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
    public suspend fun reportModelSuccess(request: ReportModelSuccessRequest, headers: Metadata = Metadata()): ReportModelSuccessResponse = unaryRpc(
      channel,
      RouterAdminServiceGrpc.getReportModelSuccessMethod(),
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
    public suspend fun listModelErrors(request: ListModelErrorsRequest, headers: Metadata = Metadata()): ListModelErrorsResponse = unaryRpc(
      channel,
      RouterAdminServiceGrpc.getListModelErrorsMethod(),
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
    public suspend fun listModelStats(request: ListModelStatsRequest, headers: Metadata = Metadata()): ListModelStatsResponse = unaryRpc(
      channel,
      RouterAdminServiceGrpc.getListModelStatsMethod(),
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
    public suspend fun resetModelError(request: ResetModelErrorRequest, headers: Metadata = Metadata()): ResetModelErrorResponse = unaryRpc(
      channel,
      RouterAdminServiceGrpc.getResetModelErrorMethod(),
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
    public suspend fun testModel(request: TestModelRequest, headers: Metadata = Metadata()): TestModelResponse = unaryRpc(
      channel,
      RouterAdminServiceGrpc.getTestModelMethod(),
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
    public suspend fun getRateLimits(request: RateLimitsRequest, headers: Metadata = Metadata()): RateLimitsResponse = unaryRpc(
      channel,
      RouterAdminServiceGrpc.getGetRateLimitsMethod(),
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
    public suspend fun invalidateClientTier(request: InvalidateClientTierRequest, headers: Metadata = Metadata()): InvalidateClientTierResponse = unaryRpc(
      channel,
      RouterAdminServiceGrpc.getInvalidateClientTierMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.router.RouterAdminService service based on Kotlin coroutines.
   */
  public abstract class RouterAdminServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.router.RouterAdminService.GetMaxContext.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getMaxContext(request: MaxContextRequest): MaxContextResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.router.RouterAdminService.GetMaxContext is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.router.RouterAdminService.ReportModelError.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun reportModelError(request: ReportModelErrorRequest): ReportModelErrorResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.router.RouterAdminService.ReportModelError is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.router.RouterAdminService.ReportModelSuccess.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun reportModelSuccess(request: ReportModelSuccessRequest): ReportModelSuccessResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.router.RouterAdminService.ReportModelSuccess is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.router.RouterAdminService.ListModelErrors.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listModelErrors(request: ListModelErrorsRequest): ListModelErrorsResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.router.RouterAdminService.ListModelErrors is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.router.RouterAdminService.ListModelStats.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listModelStats(request: ListModelStatsRequest): ListModelStatsResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.router.RouterAdminService.ListModelStats is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.router.RouterAdminService.ResetModelError.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun resetModelError(request: ResetModelErrorRequest): ResetModelErrorResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.router.RouterAdminService.ResetModelError is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.router.RouterAdminService.TestModel.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun testModel(request: TestModelRequest): TestModelResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.router.RouterAdminService.TestModel is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.router.RouterAdminService.GetRateLimits.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getRateLimits(request: RateLimitsRequest): RateLimitsResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.router.RouterAdminService.GetRateLimits is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.router.RouterAdminService.InvalidateClientTier.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun invalidateClientTier(request: InvalidateClientTierRequest): InvalidateClientTierResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.router.RouterAdminService.InvalidateClientTier is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = RouterAdminServiceGrpc.getGetMaxContextMethod(),
      implementation = ::getMaxContext
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = RouterAdminServiceGrpc.getReportModelErrorMethod(),
      implementation = ::reportModelError
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = RouterAdminServiceGrpc.getReportModelSuccessMethod(),
      implementation = ::reportModelSuccess
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = RouterAdminServiceGrpc.getListModelErrorsMethod(),
      implementation = ::listModelErrors
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = RouterAdminServiceGrpc.getListModelStatsMethod(),
      implementation = ::listModelStats
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = RouterAdminServiceGrpc.getResetModelErrorMethod(),
      implementation = ::resetModelError
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = RouterAdminServiceGrpc.getTestModelMethod(),
      implementation = ::testModel
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = RouterAdminServiceGrpc.getGetRateLimitsMethod(),
      implementation = ::getRateLimits
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = RouterAdminServiceGrpc.getInvalidateClientTierMethod(),
      implementation = ::invalidateClientTier
    )).build()
  }
}
