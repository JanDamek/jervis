package com.jervis.contracts.server

import com.jervis.contracts.server.ServerWhatsappSessionServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerWhatsappSessionService.
 */
public object ServerWhatsappSessionServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerWhatsappSessionServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val sessionEventMethod:
      MethodDescriptor<WhatsappSessionEventRequest, WhatsappSessionEventResponse>
    @JvmStatic
    get() = ServerWhatsappSessionServiceGrpc.getSessionEventMethod()

  public val capabilitiesDiscoveredMethod:
      MethodDescriptor<WhatsappCapabilitiesRequest, WhatsappCapabilitiesResponse>
    @JvmStatic
    get() = ServerWhatsappSessionServiceGrpc.getCapabilitiesDiscoveredMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerWhatsappSessionService service as suspending coroutines.
   */
  @StubFor(ServerWhatsappSessionServiceGrpc::class)
  public class ServerWhatsappSessionServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerWhatsappSessionServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerWhatsappSessionServiceCoroutineStub = ServerWhatsappSessionServiceCoroutineStub(channel, callOptions)

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
    public suspend fun sessionEvent(request: WhatsappSessionEventRequest, headers: Metadata = Metadata()): WhatsappSessionEventResponse = unaryRpc(
      channel,
      ServerWhatsappSessionServiceGrpc.getSessionEventMethod(),
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
    public suspend fun capabilitiesDiscovered(request: WhatsappCapabilitiesRequest, headers: Metadata = Metadata()): WhatsappCapabilitiesResponse = unaryRpc(
      channel,
      ServerWhatsappSessionServiceGrpc.getCapabilitiesDiscoveredMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerWhatsappSessionService service based on Kotlin coroutines.
   */
  public abstract class ServerWhatsappSessionServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerWhatsappSessionService.SessionEvent.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun sessionEvent(request: WhatsappSessionEventRequest): WhatsappSessionEventResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerWhatsappSessionService.SessionEvent is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerWhatsappSessionService.CapabilitiesDiscovered.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun capabilitiesDiscovered(request: WhatsappCapabilitiesRequest): WhatsappCapabilitiesResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerWhatsappSessionService.CapabilitiesDiscovered is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerWhatsappSessionServiceGrpc.getSessionEventMethod(),
      implementation = ::sessionEvent
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerWhatsappSessionServiceGrpc.getCapabilitiesDiscoveredMethod(),
      implementation = ::capabilitiesDiscovered
    )).build()
  }
}
