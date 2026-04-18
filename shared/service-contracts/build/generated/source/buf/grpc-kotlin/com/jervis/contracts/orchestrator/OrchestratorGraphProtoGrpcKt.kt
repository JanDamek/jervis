package com.jervis.contracts.orchestrator

import com.jervis.contracts.orchestrator.OrchestratorGraphServiceGrpc.getServiceDescriptor
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
 * jervis.orchestrator.OrchestratorGraphService.
 */
public object OrchestratorGraphServiceGrpcKt {
  public const val SERVICE_NAME: String = OrchestratorGraphServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val getTaskGraphMethod: MethodDescriptor<GetTaskGraphRequest, TaskGraphResponse>
    @JvmStatic
    get() = OrchestratorGraphServiceGrpc.getGetTaskGraphMethod()

  public val runMaintenanceMethod: MethodDescriptor<MaintenanceRunRequest, MaintenanceRunResult>
    @JvmStatic
    get() = OrchestratorGraphServiceGrpc.getRunMaintenanceMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.orchestrator.OrchestratorGraphService service as
   * suspending coroutines.
   */
  @StubFor(OrchestratorGraphServiceGrpc::class)
  public class OrchestratorGraphServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<OrchestratorGraphServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions):
        OrchestratorGraphServiceCoroutineStub = OrchestratorGraphServiceCoroutineStub(channel,
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
    public suspend fun getTaskGraph(request: GetTaskGraphRequest, headers: Metadata = Metadata()):
        TaskGraphResponse = unaryRpc(
      channel,
      OrchestratorGraphServiceGrpc.getGetTaskGraphMethod(),
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
    public suspend fun runMaintenance(request: MaintenanceRunRequest, headers: Metadata =
        Metadata()): MaintenanceRunResult = unaryRpc(
      channel,
      OrchestratorGraphServiceGrpc.getRunMaintenanceMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.orchestrator.OrchestratorGraphService service based on
   * Kotlin coroutines.
   */
  public abstract class OrchestratorGraphServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.orchestrator.OrchestratorGraphService.GetTaskGraph.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getTaskGraph(request: GetTaskGraphRequest): TaskGraphResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorGraphService.GetTaskGraph is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.orchestrator.OrchestratorGraphService.RunMaintenance.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun runMaintenance(request: MaintenanceRunRequest): MaintenanceRunResult =
        throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorGraphService.RunMaintenance is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorGraphServiceGrpc.getGetTaskGraphMethod(),
      implementation = ::getTaskGraph
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorGraphServiceGrpc.getRunMaintenanceMethod(),
      implementation = ::runMaintenance
    )).build()
  }
}
