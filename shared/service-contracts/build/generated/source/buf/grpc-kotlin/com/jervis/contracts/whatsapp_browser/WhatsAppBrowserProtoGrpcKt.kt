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
 * jervis.whatsapp_browser.WhatsAppBrowserService.
 */
public object WhatsAppBrowserServiceGrpcKt {
  public const val SERVICE_NAME: String = WhatsAppBrowserServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val sessionStatusMethod: MethodDescriptor<WhatsAppRequest, RawResponse>
    @JvmStatic
    get() = WhatsAppBrowserServiceGrpc.getSessionStatusMethod()

  public val sessionInitMethod: MethodDescriptor<WhatsAppRequest, RawResponse>
    @JvmStatic
    get() = WhatsAppBrowserServiceGrpc.getSessionInitMethod()

  public val scrapeTriggerMethod: MethodDescriptor<WhatsAppRequest, RawResponse>
    @JvmStatic
    get() = WhatsAppBrowserServiceGrpc.getScrapeTriggerMethod()

  public val scrapeLatestMethod: MethodDescriptor<WhatsAppRequest, RawResponse>
    @JvmStatic
    get() = WhatsAppBrowserServiceGrpc.getScrapeLatestMethod()

  public val vncTokenMethod: MethodDescriptor<WhatsAppRequest, RawResponse>
    @JvmStatic
    get() = WhatsAppBrowserServiceGrpc.getVncTokenMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.whatsapp_browser.WhatsAppBrowserService service as
   * suspending coroutines.
   */
  @StubFor(WhatsAppBrowserServiceGrpc::class)
  public class WhatsAppBrowserServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<WhatsAppBrowserServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions):
        WhatsAppBrowserServiceCoroutineStub = WhatsAppBrowserServiceCoroutineStub(channel,
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
    public suspend fun sessionStatus(request: WhatsAppRequest, headers: Metadata = Metadata()):
        RawResponse = unaryRpc(
      channel,
      WhatsAppBrowserServiceGrpc.getSessionStatusMethod(),
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
    public suspend fun sessionInit(request: WhatsAppRequest, headers: Metadata = Metadata()):
        RawResponse = unaryRpc(
      channel,
      WhatsAppBrowserServiceGrpc.getSessionInitMethod(),
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
    public suspend fun scrapeTrigger(request: WhatsAppRequest, headers: Metadata = Metadata()):
        RawResponse = unaryRpc(
      channel,
      WhatsAppBrowserServiceGrpc.getScrapeTriggerMethod(),
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
    public suspend fun scrapeLatest(request: WhatsAppRequest, headers: Metadata = Metadata()):
        RawResponse = unaryRpc(
      channel,
      WhatsAppBrowserServiceGrpc.getScrapeLatestMethod(),
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
    public suspend fun vncToken(request: WhatsAppRequest, headers: Metadata = Metadata()):
        RawResponse = unaryRpc(
      channel,
      WhatsAppBrowserServiceGrpc.getVncTokenMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.whatsapp_browser.WhatsAppBrowserService service based on
   * Kotlin coroutines.
   */
  public abstract class WhatsAppBrowserServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for
     * jervis.whatsapp_browser.WhatsAppBrowserService.SessionStatus.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun sessionStatus(request: WhatsAppRequest): RawResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.whatsapp_browser.WhatsAppBrowserService.SessionStatus is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.whatsapp_browser.WhatsAppBrowserService.SessionInit.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun sessionInit(request: WhatsAppRequest): RawResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.whatsapp_browser.WhatsAppBrowserService.SessionInit is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.whatsapp_browser.WhatsAppBrowserService.ScrapeTrigger.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun scrapeTrigger(request: WhatsAppRequest): RawResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.whatsapp_browser.WhatsAppBrowserService.ScrapeTrigger is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.whatsapp_browser.WhatsAppBrowserService.ScrapeLatest.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun scrapeLatest(request: WhatsAppRequest): RawResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.whatsapp_browser.WhatsAppBrowserService.ScrapeLatest is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.whatsapp_browser.WhatsAppBrowserService.VncToken.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun vncToken(request: WhatsAppRequest): RawResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.whatsapp_browser.WhatsAppBrowserService.VncToken is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = WhatsAppBrowserServiceGrpc.getSessionStatusMethod(),
      implementation = ::sessionStatus
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = WhatsAppBrowserServiceGrpc.getSessionInitMethod(),
      implementation = ::sessionInit
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = WhatsAppBrowserServiceGrpc.getScrapeTriggerMethod(),
      implementation = ::scrapeTrigger
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = WhatsAppBrowserServiceGrpc.getScrapeLatestMethod(),
      implementation = ::scrapeLatest
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = WhatsAppBrowserServiceGrpc.getVncTokenMethod(),
      implementation = ::vncToken
    )).build()
  }
}
