package com.jervis.contracts.server

import com.jervis.contracts.server.ServerKbCallbacksServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerKbCallbacksService.
 */
public object ServerKbCallbacksServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerKbCallbacksServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val kbProgressMethod: MethodDescriptor<KbProgressRequest, AckResponse>
    @JvmStatic
    get() = ServerKbCallbacksServiceGrpc.getKbProgressMethod()

  public val kbDoneMethod: MethodDescriptor<KbDoneRequest, AckResponse>
    @JvmStatic
    get() = ServerKbCallbacksServiceGrpc.getKbDoneMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerKbCallbacksService service as suspending coroutines.
   */
  @StubFor(ServerKbCallbacksServiceGrpc::class)
  public class ServerKbCallbacksServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerKbCallbacksServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerKbCallbacksServiceCoroutineStub = ServerKbCallbacksServiceCoroutineStub(channel, callOptions)

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
    public suspend fun kbProgress(request: KbProgressRequest, headers: Metadata = Metadata()): AckResponse = unaryRpc(
      channel,
      ServerKbCallbacksServiceGrpc.getKbProgressMethod(),
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
    public suspend fun kbDone(request: KbDoneRequest, headers: Metadata = Metadata()): AckResponse = unaryRpc(
      channel,
      ServerKbCallbacksServiceGrpc.getKbDoneMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerKbCallbacksService service based on Kotlin coroutines.
   */
  public abstract class ServerKbCallbacksServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerKbCallbacksService.KbProgress.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun kbProgress(request: KbProgressRequest): AckResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerKbCallbacksService.KbProgress is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerKbCallbacksService.KbDone.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun kbDone(request: KbDoneRequest): AckResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerKbCallbacksService.KbDone is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerKbCallbacksServiceGrpc.getKbProgressMethod(),
      implementation = ::kbProgress
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerKbCallbacksServiceGrpc.getKbDoneMethod(),
      implementation = ::kbDone
    )).build()
  }
}
