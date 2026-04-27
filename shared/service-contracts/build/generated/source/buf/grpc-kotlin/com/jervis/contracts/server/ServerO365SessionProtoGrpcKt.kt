package com.jervis.contracts.server

import com.jervis.contracts.server.ServerO365SessionServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerO365SessionService.
 */
public object ServerO365SessionServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerO365SessionServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val sessionEventMethod: MethodDescriptor<SessionEventRequest, SessionEventResponse>
    @JvmStatic
    get() = ServerO365SessionServiceGrpc.getSessionEventMethod()

  public val capabilitiesDiscoveredMethod:
      MethodDescriptor<CapabilitiesDiscoveredRequest, CapabilitiesDiscoveredResponse>
    @JvmStatic
    get() = ServerO365SessionServiceGrpc.getCapabilitiesDiscoveredMethod()

  public val notifyMethod: MethodDescriptor<NotifyRequest, NotifyResponse>
    @JvmStatic
    get() = ServerO365SessionServiceGrpc.getNotifyMethod()

  public val acquireLoginConsentMethod:
      MethodDescriptor<AcquireLoginConsentRequest, AcquireLoginConsentResponse>
    @JvmStatic
    get() = ServerO365SessionServiceGrpc.getAcquireLoginConsentMethod()

  public val waitLoginConsentMethod:
      MethodDescriptor<WaitLoginConsentRequest, WaitLoginConsentResponse>
    @JvmStatic
    get() = ServerO365SessionServiceGrpc.getWaitLoginConsentMethod()

  public val releaseLoginConsentMethod:
      MethodDescriptor<ReleaseLoginConsentRequest, ReleaseLoginConsentResponse>
    @JvmStatic
    get() = ServerO365SessionServiceGrpc.getReleaseLoginConsentMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerO365SessionService service as suspending coroutines.
   */
  @StubFor(ServerO365SessionServiceGrpc::class)
  public class ServerO365SessionServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerO365SessionServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerO365SessionServiceCoroutineStub = ServerO365SessionServiceCoroutineStub(channel, callOptions)

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
    public suspend fun sessionEvent(request: SessionEventRequest, headers: Metadata = Metadata()): SessionEventResponse = unaryRpc(
      channel,
      ServerO365SessionServiceGrpc.getSessionEventMethod(),
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
    public suspend fun capabilitiesDiscovered(request: CapabilitiesDiscoveredRequest, headers: Metadata = Metadata()): CapabilitiesDiscoveredResponse = unaryRpc(
      channel,
      ServerO365SessionServiceGrpc.getCapabilitiesDiscoveredMethod(),
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
    public suspend fun notify(request: NotifyRequest, headers: Metadata = Metadata()): NotifyResponse = unaryRpc(
      channel,
      ServerO365SessionServiceGrpc.getNotifyMethod(),
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
    public suspend fun acquireLoginConsent(request: AcquireLoginConsentRequest, headers: Metadata = Metadata()): AcquireLoginConsentResponse = unaryRpc(
      channel,
      ServerO365SessionServiceGrpc.getAcquireLoginConsentMethod(),
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
    public suspend fun waitLoginConsent(request: WaitLoginConsentRequest, headers: Metadata = Metadata()): WaitLoginConsentResponse = unaryRpc(
      channel,
      ServerO365SessionServiceGrpc.getWaitLoginConsentMethod(),
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
    public suspend fun releaseLoginConsent(request: ReleaseLoginConsentRequest, headers: Metadata = Metadata()): ReleaseLoginConsentResponse = unaryRpc(
      channel,
      ServerO365SessionServiceGrpc.getReleaseLoginConsentMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerO365SessionService service based on Kotlin coroutines.
   */
  public abstract class ServerO365SessionServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerO365SessionService.SessionEvent.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun sessionEvent(request: SessionEventRequest): SessionEventResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerO365SessionService.SessionEvent is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerO365SessionService.CapabilitiesDiscovered.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun capabilitiesDiscovered(request: CapabilitiesDiscoveredRequest): CapabilitiesDiscoveredResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerO365SessionService.CapabilitiesDiscovered is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerO365SessionService.Notify.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun notify(request: NotifyRequest): NotifyResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerO365SessionService.Notify is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerO365SessionService.AcquireLoginConsent.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun acquireLoginConsent(request: AcquireLoginConsentRequest): AcquireLoginConsentResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerO365SessionService.AcquireLoginConsent is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerO365SessionService.WaitLoginConsent.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun waitLoginConsent(request: WaitLoginConsentRequest): WaitLoginConsentResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerO365SessionService.WaitLoginConsent is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerO365SessionService.ReleaseLoginConsent.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun releaseLoginConsent(request: ReleaseLoginConsentRequest): ReleaseLoginConsentResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerO365SessionService.ReleaseLoginConsent is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerO365SessionServiceGrpc.getSessionEventMethod(),
      implementation = ::sessionEvent
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerO365SessionServiceGrpc.getCapabilitiesDiscoveredMethod(),
      implementation = ::capabilitiesDiscovered
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerO365SessionServiceGrpc.getNotifyMethod(),
      implementation = ::notify
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerO365SessionServiceGrpc.getAcquireLoginConsentMethod(),
      implementation = ::acquireLoginConsent
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerO365SessionServiceGrpc.getWaitLoginConsentMethod(),
      implementation = ::waitLoginConsent
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerO365SessionServiceGrpc.getReleaseLoginConsentMethod(),
      implementation = ::releaseLoginConsent
    )).build()
  }
}
