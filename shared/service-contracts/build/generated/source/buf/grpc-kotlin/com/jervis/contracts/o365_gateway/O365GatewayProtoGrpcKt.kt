package com.jervis.contracts.o365_gateway

import com.jervis.contracts.o365_gateway.O365GatewayServiceGrpc.getServiceDescriptor
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
 * jervis.o365_gateway.O365GatewayService.
 */
public object O365GatewayServiceGrpcKt {
  public const val SERVICE_NAME: String = O365GatewayServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val requestMethod: MethodDescriptor<O365Request, O365Response>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getRequestMethod()

  public val requestBytesMethod: MethodDescriptor<O365Request, O365BytesResponse>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getRequestBytesMethod()

  public val listChatsMethod: MethodDescriptor<ListChatsRequest, ListChatsResponse>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getListChatsMethod()

  public val readChatMethod: MethodDescriptor<ReadChatRequest, ListChatMessagesResponse>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getReadChatMethod()

  public val sendChatMessageMethod: MethodDescriptor<SendChatMessageRequest, ChatMessage>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getSendChatMessageMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.o365_gateway.O365GatewayService service as suspending
   * coroutines.
   */
  @StubFor(O365GatewayServiceGrpc::class)
  public class O365GatewayServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<O365GatewayServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): O365GatewayServiceCoroutineStub
        = O365GatewayServiceCoroutineStub(channel, callOptions)

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
    public suspend fun request(request: O365Request, headers: Metadata = Metadata()): O365Response =
        unaryRpc(
      channel,
      O365GatewayServiceGrpc.getRequestMethod(),
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
    public suspend fun requestBytes(request: O365Request, headers: Metadata = Metadata()):
        O365BytesResponse = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getRequestBytesMethod(),
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
    public suspend fun listChats(request: ListChatsRequest, headers: Metadata = Metadata()):
        ListChatsResponse = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getListChatsMethod(),
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
    public suspend fun readChat(request: ReadChatRequest, headers: Metadata = Metadata()):
        ListChatMessagesResponse = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getReadChatMethod(),
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
    public suspend fun sendChatMessage(request: SendChatMessageRequest, headers: Metadata =
        Metadata()): ChatMessage = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getSendChatMessageMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.o365_gateway.O365GatewayService service based on Kotlin
   * coroutines.
   */
  public abstract class O365GatewayServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.Request.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun request(request: O365Request): O365Response = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.Request is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.RequestBytes.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun requestBytes(request: O365Request): O365BytesResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.RequestBytes is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.ListChats.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listChats(request: ListChatsRequest): ListChatsResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.ListChats is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.ReadChat.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun readChat(request: ReadChatRequest): ListChatMessagesResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.ReadChat is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.SendChatMessage.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun sendChatMessage(request: SendChatMessageRequest): ChatMessage = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.SendChatMessage is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getRequestMethod(),
      implementation = ::request
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getRequestBytesMethod(),
      implementation = ::requestBytes
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getListChatsMethod(),
      implementation = ::listChats
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getReadChatMethod(),
      implementation = ::readChat
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getSendChatMessageMethod(),
      implementation = ::sendChatMessage
    )).build()
  }
}
