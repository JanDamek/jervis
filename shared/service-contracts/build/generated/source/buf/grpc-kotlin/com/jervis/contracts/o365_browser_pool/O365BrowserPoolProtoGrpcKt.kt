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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.o365_browser_pool.O365BrowserPoolService.
 */
public object O365BrowserPoolServiceGrpcKt {
  public const val SERVICE_NAME: String = O365BrowserPoolServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val healthMethod: MethodDescriptor<HealthRequest, HealthResponse>
    @JvmStatic
    get() = O365BrowserPoolServiceGrpc.getHealthMethod()

  public val getSessionMethod: MethodDescriptor<SessionRef, SessionStatus>
    @JvmStatic
    get() = O365BrowserPoolServiceGrpc.getGetSessionMethod()

  public val initSessionMethod: MethodDescriptor<InitSessionRequest, InitSessionResponse>
    @JvmStatic
    get() = O365BrowserPoolServiceGrpc.getInitSessionMethod()

  public val submitMfaMethod: MethodDescriptor<SubmitMfaRequest, InitSessionResponse>
    @JvmStatic
    get() = O365BrowserPoolServiceGrpc.getSubmitMfaMethod()

  public val pushInstructionMethod: MethodDescriptor<InstructionRequest, InstructionResponse>
    @JvmStatic
    get() = O365BrowserPoolServiceGrpc.getPushInstructionMethod()

  public val streamVncMethod: MethodDescriptor<VncFrame, VncFrame>
    @JvmStatic
    get() = O365BrowserPoolServiceGrpc.getStreamVncMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.o365_browser_pool.O365BrowserPoolService service as suspending coroutines.
   */
  @StubFor(O365BrowserPoolServiceGrpc::class)
  public class O365BrowserPoolServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<O365BrowserPoolServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): O365BrowserPoolServiceCoroutineStub = O365BrowserPoolServiceCoroutineStub(channel, callOptions)

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
    public suspend fun health(request: HealthRequest, headers: Metadata = Metadata()): HealthResponse = unaryRpc(
      channel,
      O365BrowserPoolServiceGrpc.getHealthMethod(),
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
    public suspend fun getSession(request: SessionRef, headers: Metadata = Metadata()): SessionStatus = unaryRpc(
      channel,
      O365BrowserPoolServiceGrpc.getGetSessionMethod(),
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
      O365BrowserPoolServiceGrpc.getInitSessionMethod(),
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
    public suspend fun submitMfa(request: SubmitMfaRequest, headers: Metadata = Metadata()): InitSessionResponse = unaryRpc(
      channel,
      O365BrowserPoolServiceGrpc.getSubmitMfaMethod(),
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
    public suspend fun pushInstruction(request: InstructionRequest, headers: Metadata = Metadata()): InstructionResponse = unaryRpc(
      channel,
      O365BrowserPoolServiceGrpc.getPushInstructionMethod(),
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
      O365BrowserPoolServiceGrpc.getStreamVncMethod(),
      requests,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.o365_browser_pool.O365BrowserPoolService service based on Kotlin coroutines.
   */
  public abstract class O365BrowserPoolServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.o365_browser_pool.O365BrowserPoolService.Health.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun health(request: HealthRequest): HealthResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_browser_pool.O365BrowserPoolService.Health is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_browser_pool.O365BrowserPoolService.GetSession.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getSession(request: SessionRef): SessionStatus = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_browser_pool.O365BrowserPoolService.GetSession is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_browser_pool.O365BrowserPoolService.InitSession.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun initSession(request: InitSessionRequest): InitSessionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_browser_pool.O365BrowserPoolService.InitSession is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_browser_pool.O365BrowserPoolService.SubmitMfa.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun submitMfa(request: SubmitMfaRequest): InitSessionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_browser_pool.O365BrowserPoolService.SubmitMfa is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_browser_pool.O365BrowserPoolService.PushInstruction.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun pushInstruction(request: InstructionRequest): InstructionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_browser_pool.O365BrowserPoolService.PushInstruction is unimplemented"))

    /**
     * Returns a [Flow] of responses to an RPC for jervis.o365_browser_pool.O365BrowserPoolService.StreamVnc.
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
    public open fun streamVnc(requests: Flow<VncFrame>): Flow<VncFrame> = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_browser_pool.O365BrowserPoolService.StreamVnc is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365BrowserPoolServiceGrpc.getHealthMethod(),
      implementation = ::health
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365BrowserPoolServiceGrpc.getGetSessionMethod(),
      implementation = ::getSession
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365BrowserPoolServiceGrpc.getInitSessionMethod(),
      implementation = ::initSession
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365BrowserPoolServiceGrpc.getSubmitMfaMethod(),
      implementation = ::submitMfa
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365BrowserPoolServiceGrpc.getPushInstructionMethod(),
      implementation = ::pushInstruction
    ))
      .addMethod(bidiStreamingServerMethodDefinition(
      context = this.context,
      descriptor = O365BrowserPoolServiceGrpc.getStreamVncMethod(),
      implementation = ::streamVnc
    )).build()
  }
}
