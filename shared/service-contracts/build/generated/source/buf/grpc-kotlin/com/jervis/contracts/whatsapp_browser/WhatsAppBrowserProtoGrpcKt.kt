package com.jervis.contracts.whatsapp_browser

import com.jervis.contracts.whatsapp_browser.WhatsAppBrowserServiceGrpc.getServiceDescriptor
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
import io.grpc.kotlin.ClientCalls.bidiStreamingRpc
import io.grpc.kotlin.ClientCalls.unaryRpc
import io.grpc.kotlin.ServerCalls.bidiStreamingServerMethodDefinition
import io.grpc.kotlin.ServerCalls.unaryServerMethodDefinition
import io.grpc.kotlin.StubFor
import kotlin.String
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlinx.coroutines.flow.Flow

/**
 * Holder for Kotlin coroutine-based client and server APIs for jervis.whatsapp_browser.WhatsAppBrowserService.
 */
public object WhatsAppBrowserServiceGrpcKt {
  public const val SERVICE_NAME: String = WhatsAppBrowserServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val getSessionMethod: MethodDescriptor<SessionRef, SessionStatus>
    @JvmStatic
    get() = WhatsAppBrowserServiceGrpc.getGetSessionMethod()

  public val initSessionMethod: MethodDescriptor<InitSessionRequest, InitSessionResponse>
    @JvmStatic
    get() = WhatsAppBrowserServiceGrpc.getInitSessionMethod()

  public val triggerScrapeMethod: MethodDescriptor<TriggerScrapeRequest, TriggerScrapeResponse>
    @JvmStatic
    get() = WhatsAppBrowserServiceGrpc.getTriggerScrapeMethod()

  public val getLatestScrapeMethod: MethodDescriptor<SessionRef, LatestScrapeResponse>
    @JvmStatic
    get() = WhatsAppBrowserServiceGrpc.getGetLatestScrapeMethod()

  public val streamVncMethod: MethodDescriptor<VncFrame, VncFrame>
    @JvmStatic
    get() = WhatsAppBrowserServiceGrpc.getStreamVncMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.whatsapp_browser.WhatsAppBrowserService service as suspending coroutines.
   */
  @StubFor(WhatsAppBrowserServiceGrpc::class)
  public class WhatsAppBrowserServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<WhatsAppBrowserServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): WhatsAppBrowserServiceCoroutineStub = WhatsAppBrowserServiceCoroutineStub(channel, callOptions)

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
    public suspend fun getSession(request: SessionRef, headers: Metadata = Metadata()): SessionStatus = unaryRpc(
      channel,
      WhatsAppBrowserServiceGrpc.getGetSessionMethod(),
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
    public suspend fun initSession(request: InitSessionRequest, headers: Metadata = Metadata()): InitSessionResponse = unaryRpc(
      channel,
      WhatsAppBrowserServiceGrpc.getInitSessionMethod(),
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
    public suspend fun triggerScrape(request: TriggerScrapeRequest, headers: Metadata = Metadata()): TriggerScrapeResponse = unaryRpc(
      channel,
      WhatsAppBrowserServiceGrpc.getTriggerScrapeMethod(),
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
    public suspend fun getLatestScrape(request: SessionRef, headers: Metadata = Metadata()): LatestScrapeResponse = unaryRpc(
      channel,
      WhatsAppBrowserServiceGrpc.getGetLatestScrapeMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Returns a [Flow] that, when collected, executes this RPC and emits responses from the
     * server as they arrive.  That flow finishes normally if the server closes its response with
     * [`Status.OK`][io.grpc.Status], and fails by throwing a [StatusException] otherwise.  If
     * collecting the flow downstream fails exceptionally (including via cancellation), the RPC
     * is cancelled with that exception as a cause.
     *
     * The [Flow] of requests is collected once each time the [Flow] of responses is
     * collected. If collection of the [Flow] of responses completes normally or
     * exceptionally before collection of `requests` completes, the collection of
     * `requests` is cancelled.  If the collection of `requests` completes
     * exceptionally for any other reason, then the collection of the [Flow] of responses
     * completes exceptionally for the same reason and the RPC is cancelled with that reason.
     *
     * @param requests A [Flow] of request messages.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return A flow that, when collected, emits the responses from the server.
     */
    public fun streamVnc(requests: Flow<VncFrame>, headers: Metadata = Metadata()): Flow<VncFrame> = bidiStreamingRpc(
      channel,
      WhatsAppBrowserServiceGrpc.getStreamVncMethod(),
      requests,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.whatsapp_browser.WhatsAppBrowserService service based on Kotlin coroutines.
   */
  public abstract class WhatsAppBrowserServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.whatsapp_browser.WhatsAppBrowserService.GetSession.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getSession(request: SessionRef): SessionStatus = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.whatsapp_browser.WhatsAppBrowserService.GetSession is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.whatsapp_browser.WhatsAppBrowserService.InitSession.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun initSession(request: InitSessionRequest): InitSessionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.whatsapp_browser.WhatsAppBrowserService.InitSession is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.whatsapp_browser.WhatsAppBrowserService.TriggerScrape.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun triggerScrape(request: TriggerScrapeRequest): TriggerScrapeResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.whatsapp_browser.WhatsAppBrowserService.TriggerScrape is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.whatsapp_browser.WhatsAppBrowserService.GetLatestScrape.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getLatestScrape(request: SessionRef): LatestScrapeResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.whatsapp_browser.WhatsAppBrowserService.GetLatestScrape is unimplemented"))

    /**
     * Returns a [Flow] of responses to an RPC for jervis.whatsapp_browser.WhatsAppBrowserService.StreamVnc.
     *
     * If creating or collecting the returned flow fails with a [StatusException], the RPC
     * will fail with the corresponding [io.grpc.Status].  If it fails with a
     * [java.util.concurrent.CancellationException], the RPC will fail with status `Status.CANCELLED`.  If creating
     * or collecting the returned flow fails for any other reason, the RPC will fail with
     * `Status.UNKNOWN` with the exception as a cause.
     *
     * @param requests A [Flow] of requests from the client.  This flow can be
     *        collected only once and throws [java.lang.IllegalStateException] on attempts to collect
     *        it more than once.
     */
    public open fun streamVnc(requests: Flow<VncFrame>): Flow<VncFrame> = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.whatsapp_browser.WhatsAppBrowserService.StreamVnc is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = WhatsAppBrowserServiceGrpc.getGetSessionMethod(),
      implementation = ::getSession
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = WhatsAppBrowserServiceGrpc.getInitSessionMethod(),
      implementation = ::initSession
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = WhatsAppBrowserServiceGrpc.getTriggerScrapeMethod(),
      implementation = ::triggerScrape
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = WhatsAppBrowserServiceGrpc.getGetLatestScrapeMethod(),
      implementation = ::getLatestScrape
    ))
      .addMethod(bidiStreamingServerMethodDefinition(
      context = this.context,
      descriptor = WhatsAppBrowserServiceGrpc.getStreamVncMethod(),
      implementation = ::streamVnc
    )).build()
  }
}
