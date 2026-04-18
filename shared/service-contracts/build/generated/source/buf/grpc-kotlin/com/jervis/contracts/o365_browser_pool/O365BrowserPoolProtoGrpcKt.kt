package com.jervis.contracts.o365_browser_pool

import com.jervis.contracts.o365_browser_pool.O365BrowserPoolServiceGrpc.getServiceDescriptor
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
 * jervis.o365_browser_pool.O365BrowserPoolService.
 */
public object O365BrowserPoolServiceGrpcKt {
  public const val SERVICE_NAME: String = O365BrowserPoolServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val healthMethod: MethodDescriptor<HealthRequest, RawResponse>
    @JvmStatic
    get() = O365BrowserPoolServiceGrpc.getHealthMethod()

  public val sessionStatusMethod: MethodDescriptor<PodRequest, RawResponse>
    @JvmStatic
    get() = O365BrowserPoolServiceGrpc.getSessionStatusMethod()

  public val sessionInitMethod: MethodDescriptor<PodRequest, RawResponse>
    @JvmStatic
    get() = O365BrowserPoolServiceGrpc.getSessionInitMethod()

  public val sessionMfaMethod: MethodDescriptor<PodRequest, RawResponse>
    @JvmStatic
    get() = O365BrowserPoolServiceGrpc.getSessionMfaMethod()

  public val sessionRediscoverMethod: MethodDescriptor<PodRequest, RawResponse>
    @JvmStatic
    get() = O365BrowserPoolServiceGrpc.getSessionRediscoverMethod()

  public val scrapeDiscoverMethod: MethodDescriptor<PodRequest, RawResponse>
    @JvmStatic
    get() = O365BrowserPoolServiceGrpc.getScrapeDiscoverMethod()

  public val vncTokenMethod: MethodDescriptor<PodRequest, RawResponse>
    @JvmStatic
    get() = O365BrowserPoolServiceGrpc.getVncTokenMethod()

  public val sendInstructionMethod: MethodDescriptor<PodRequest, RawResponse>
    @JvmStatic
    get() = O365BrowserPoolServiceGrpc.getSendInstructionMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.o365_browser_pool.O365BrowserPoolService service as
   * suspending coroutines.
   */
  @StubFor(O365BrowserPoolServiceGrpc::class)
  public class O365BrowserPoolServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<O365BrowserPoolServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions):
        O365BrowserPoolServiceCoroutineStub = O365BrowserPoolServiceCoroutineStub(channel,
        callOptions)

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
    public suspend fun health(request: HealthRequest, headers: Metadata = Metadata()): RawResponse =
        unaryRpc(
      channel,
      O365BrowserPoolServiceGrpc.getHealthMethod(),
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
    public suspend fun sessionStatus(request: PodRequest, headers: Metadata = Metadata()):
        RawResponse = unaryRpc(
      channel,
      O365BrowserPoolServiceGrpc.getSessionStatusMethod(),
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
    public suspend fun sessionInit(request: PodRequest, headers: Metadata = Metadata()): RawResponse
        = unaryRpc(
      channel,
      O365BrowserPoolServiceGrpc.getSessionInitMethod(),
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
    public suspend fun sessionMfa(request: PodRequest, headers: Metadata = Metadata()): RawResponse
        = unaryRpc(
      channel,
      O365BrowserPoolServiceGrpc.getSessionMfaMethod(),
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
    public suspend fun sessionRediscover(request: PodRequest, headers: Metadata = Metadata()):
        RawResponse = unaryRpc(
      channel,
      O365BrowserPoolServiceGrpc.getSessionRediscoverMethod(),
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
    public suspend fun scrapeDiscover(request: PodRequest, headers: Metadata = Metadata()):
        RawResponse = unaryRpc(
      channel,
      O365BrowserPoolServiceGrpc.getScrapeDiscoverMethod(),
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
    public suspend fun vncToken(request: PodRequest, headers: Metadata = Metadata()): RawResponse =
        unaryRpc(
      channel,
      O365BrowserPoolServiceGrpc.getVncTokenMethod(),
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
    public suspend fun sendInstruction(request: PodRequest, headers: Metadata = Metadata()):
        RawResponse = unaryRpc(
      channel,
      O365BrowserPoolServiceGrpc.getSendInstructionMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.o365_browser_pool.O365BrowserPoolService service based on
   * Kotlin coroutines.
   */
  public abstract class O365BrowserPoolServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.o365_browser_pool.O365BrowserPoolService.Health.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun health(request: HealthRequest): RawResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_browser_pool.O365BrowserPoolService.Health is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.o365_browser_pool.O365BrowserPoolService.SessionStatus.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun sessionStatus(request: PodRequest): RawResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_browser_pool.O365BrowserPoolService.SessionStatus is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.o365_browser_pool.O365BrowserPoolService.SessionInit.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun sessionInit(request: PodRequest): RawResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_browser_pool.O365BrowserPoolService.SessionInit is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.o365_browser_pool.O365BrowserPoolService.SessionMfa.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun sessionMfa(request: PodRequest): RawResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_browser_pool.O365BrowserPoolService.SessionMfa is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.o365_browser_pool.O365BrowserPoolService.SessionRediscover.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun sessionRediscover(request: PodRequest): RawResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_browser_pool.O365BrowserPoolService.SessionRediscover is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.o365_browser_pool.O365BrowserPoolService.ScrapeDiscover.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun scrapeDiscover(request: PodRequest): RawResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_browser_pool.O365BrowserPoolService.ScrapeDiscover is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_browser_pool.O365BrowserPoolService.VncToken.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun vncToken(request: PodRequest): RawResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_browser_pool.O365BrowserPoolService.VncToken is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.o365_browser_pool.O365BrowserPoolService.SendInstruction.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun sendInstruction(request: PodRequest): RawResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_browser_pool.O365BrowserPoolService.SendInstruction is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365BrowserPoolServiceGrpc.getHealthMethod(),
      implementation = ::health
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365BrowserPoolServiceGrpc.getSessionStatusMethod(),
      implementation = ::sessionStatus
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365BrowserPoolServiceGrpc.getSessionInitMethod(),
      implementation = ::sessionInit
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365BrowserPoolServiceGrpc.getSessionMfaMethod(),
      implementation = ::sessionMfa
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365BrowserPoolServiceGrpc.getSessionRediscoverMethod(),
      implementation = ::sessionRediscover
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365BrowserPoolServiceGrpc.getScrapeDiscoverMethod(),
      implementation = ::scrapeDiscover
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365BrowserPoolServiceGrpc.getVncTokenMethod(),
      implementation = ::vncToken
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365BrowserPoolServiceGrpc.getSendInstructionMethod(),
      implementation = ::sendInstruction
    )).build()
  }
}
