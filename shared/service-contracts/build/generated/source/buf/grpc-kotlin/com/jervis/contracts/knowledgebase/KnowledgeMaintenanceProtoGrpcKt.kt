package com.jervis.contracts.knowledgebase

import com.jervis.contracts.knowledgebase.KnowledgeMaintenanceServiceGrpc.getServiceDescriptor
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
 * jervis.knowledgebase.KnowledgeMaintenanceService.
 */
public object KnowledgeMaintenanceServiceGrpcKt {
  public const val SERVICE_NAME: String = KnowledgeMaintenanceServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val runBatchMethod: MethodDescriptor<MaintenanceBatchRequest, MaintenanceBatchResult>
    @JvmStatic
    get() = KnowledgeMaintenanceServiceGrpc.getRunBatchMethod()

  public val retagProjectMethod: MethodDescriptor<RetagProjectRequest, RetagResult>
    @JvmStatic
    get() = KnowledgeMaintenanceServiceGrpc.getRetagProjectMethod()

  public val retagGroupMethod: MethodDescriptor<RetagGroupRequest, RetagResult>
    @JvmStatic
    get() = KnowledgeMaintenanceServiceGrpc.getRetagGroupMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.knowledgebase.KnowledgeMaintenanceService service as
   * suspending coroutines.
   */
  @StubFor(KnowledgeMaintenanceServiceGrpc::class)
  public class KnowledgeMaintenanceServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<KnowledgeMaintenanceServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions):
        KnowledgeMaintenanceServiceCoroutineStub = KnowledgeMaintenanceServiceCoroutineStub(channel,
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
    public suspend fun runBatch(request: MaintenanceBatchRequest, headers: Metadata = Metadata()):
        MaintenanceBatchResult = unaryRpc(
      channel,
      KnowledgeMaintenanceServiceGrpc.getRunBatchMethod(),
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
    public suspend fun retagProject(request: RetagProjectRequest, headers: Metadata = Metadata()):
        RetagResult = unaryRpc(
      channel,
      KnowledgeMaintenanceServiceGrpc.getRetagProjectMethod(),
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
    public suspend fun retagGroup(request: RetagGroupRequest, headers: Metadata = Metadata()):
        RetagResult = unaryRpc(
      channel,
      KnowledgeMaintenanceServiceGrpc.getRetagGroupMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.knowledgebase.KnowledgeMaintenanceService service based
   * on Kotlin coroutines.
   */
  public abstract class KnowledgeMaintenanceServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeMaintenanceService.RunBatch.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun runBatch(request: MaintenanceBatchRequest): MaintenanceBatchResult =
        throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeMaintenanceService.RunBatch is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.knowledgebase.KnowledgeMaintenanceService.RetagProject.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun retagProject(request: RetagProjectRequest): RetagResult = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeMaintenanceService.RetagProject is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.knowledgebase.KnowledgeMaintenanceService.RetagGroup.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun retagGroup(request: RetagGroupRequest): RetagResult = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeMaintenanceService.RetagGroup is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeMaintenanceServiceGrpc.getRunBatchMethod(),
      implementation = ::runBatch
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeMaintenanceServiceGrpc.getRetagProjectMethod(),
      implementation = ::retagProject
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeMaintenanceServiceGrpc.getRetagGroupMethod(),
      implementation = ::retagGroup
    )).build()
  }
}
