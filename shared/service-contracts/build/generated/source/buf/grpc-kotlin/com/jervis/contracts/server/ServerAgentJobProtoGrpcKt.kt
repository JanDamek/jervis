package com.jervis.contracts.server

import com.jervis.contracts.server.ServerAgentJobServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerAgentJobService.
 */
public object ServerAgentJobServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerAgentJobServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val dispatchAgentJobMethod:
      MethodDescriptor<DispatchAgentJobRequest, DispatchAgentJobResponse>
    @JvmStatic
    get() = ServerAgentJobServiceGrpc.getDispatchAgentJobMethod()

  public val getAgentJobStatusMethod: MethodDescriptor<AgentJobIdRequest, GetAgentJobStatusResponse>
    @JvmStatic
    get() = ServerAgentJobServiceGrpc.getGetAgentJobStatusMethod()

  public val abortAgentJobMethod: MethodDescriptor<AbortAgentJobRequest, AbortAgentJobResponse>
    @JvmStatic
    get() = ServerAgentJobServiceGrpc.getAbortAgentJobMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerAgentJobService service as suspending coroutines.
   */
  @StubFor(ServerAgentJobServiceGrpc::class)
  public class ServerAgentJobServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerAgentJobServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerAgentJobServiceCoroutineStub = ServerAgentJobServiceCoroutineStub(channel, callOptions)

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
    public suspend fun dispatchAgentJob(request: DispatchAgentJobRequest, headers: Metadata = Metadata()): DispatchAgentJobResponse = unaryRpc(
      channel,
      ServerAgentJobServiceGrpc.getDispatchAgentJobMethod(),
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
    public suspend fun getAgentJobStatus(request: AgentJobIdRequest, headers: Metadata = Metadata()): GetAgentJobStatusResponse = unaryRpc(
      channel,
      ServerAgentJobServiceGrpc.getGetAgentJobStatusMethod(),
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
    public suspend fun abortAgentJob(request: AbortAgentJobRequest, headers: Metadata = Metadata()): AbortAgentJobResponse = unaryRpc(
      channel,
      ServerAgentJobServiceGrpc.getAbortAgentJobMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerAgentJobService service based on Kotlin coroutines.
   */
  public abstract class ServerAgentJobServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerAgentJobService.DispatchAgentJob.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun dispatchAgentJob(request: DispatchAgentJobRequest): DispatchAgentJobResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerAgentJobService.DispatchAgentJob is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerAgentJobService.GetAgentJobStatus.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getAgentJobStatus(request: AgentJobIdRequest): GetAgentJobStatusResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerAgentJobService.GetAgentJobStatus is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerAgentJobService.AbortAgentJob.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun abortAgentJob(request: AbortAgentJobRequest): AbortAgentJobResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerAgentJobService.AbortAgentJob is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerAgentJobServiceGrpc.getDispatchAgentJobMethod(),
      implementation = ::dispatchAgentJob
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerAgentJobServiceGrpc.getGetAgentJobStatusMethod(),
      implementation = ::getAgentJobStatus
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerAgentJobServiceGrpc.getAbortAgentJobMethod(),
      implementation = ::abortAgentJob
    )).build()
  }
}
