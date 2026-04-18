package com.jervis.contracts.server

import com.jervis.contracts.server.ServerGuidelinesServiceGrpc.getServiceDescriptor
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
 * jervis.server.ServerGuidelinesService.
 */
public object ServerGuidelinesServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerGuidelinesServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val getMergedMethod: MethodDescriptor<GetMergedRequest, MergedGuidelines>
    @JvmStatic
    get() = ServerGuidelinesServiceGrpc.getGetMergedMethod()

  public val getMethod: MethodDescriptor<GetRequest, GuidelinesDocument>
    @JvmStatic
    get() = ServerGuidelinesServiceGrpc.getGetMethod()

  public val setMethod: MethodDescriptor<SetRequest, GuidelinesDocument>
    @JvmStatic
    get() = ServerGuidelinesServiceGrpc.getSetMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerGuidelinesService service as suspending
   * coroutines.
   */
  @StubFor(ServerGuidelinesServiceGrpc::class)
  public class ServerGuidelinesServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerGuidelinesServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions):
        ServerGuidelinesServiceCoroutineStub = ServerGuidelinesServiceCoroutineStub(channel,
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
    public suspend fun getMerged(request: GetMergedRequest, headers: Metadata = Metadata()):
        MergedGuidelines = unaryRpc(
      channel,
      ServerGuidelinesServiceGrpc.getGetMergedMethod(),
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
    public suspend fun `get`(request: GetRequest, headers: Metadata = Metadata()):
        GuidelinesDocument = unaryRpc(
      channel,
      ServerGuidelinesServiceGrpc.getGetMethod(),
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
    public suspend fun `set`(request: SetRequest, headers: Metadata = Metadata()):
        GuidelinesDocument = unaryRpc(
      channel,
      ServerGuidelinesServiceGrpc.getSetMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerGuidelinesService service based on Kotlin
   * coroutines.
   */
  public abstract class ServerGuidelinesServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerGuidelinesService.GetMerged.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getMerged(request: GetMergedRequest): MergedGuidelines = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerGuidelinesService.GetMerged is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerGuidelinesService.Get.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun `get`(request: GetRequest): GuidelinesDocument = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerGuidelinesService.Get is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerGuidelinesService.Set.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun `set`(request: SetRequest): GuidelinesDocument = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerGuidelinesService.Set is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerGuidelinesServiceGrpc.getGetMergedMethod(),
      implementation = ::getMerged
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerGuidelinesServiceGrpc.getGetMethod(),
      implementation = ::`get`
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerGuidelinesServiceGrpc.getSetMethod(),
      implementation = ::`set`
    )).build()
  }
}
