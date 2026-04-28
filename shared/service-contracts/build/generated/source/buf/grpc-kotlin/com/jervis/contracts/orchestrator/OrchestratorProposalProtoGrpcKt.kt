package com.jervis.contracts.orchestrator

import com.jervis.contracts.orchestrator.OrchestratorProposalServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.orchestrator.OrchestratorProposalService.
 */
public object OrchestratorProposalServiceGrpcKt {
  public const val SERVICE_NAME: String = OrchestratorProposalServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val proposeTaskMethod: MethodDescriptor<ProposeTaskRequest, ProposeTaskResponse>
    @JvmStatic
    get() = OrchestratorProposalServiceGrpc.getProposeTaskMethod()

  public val updateProposedTaskMethod:
      MethodDescriptor<UpdateProposedTaskRequest, UpdateProposedTaskResponse>
    @JvmStatic
    get() = OrchestratorProposalServiceGrpc.getUpdateProposedTaskMethod()

  public val sendForApprovalMethod: MethodDescriptor<TaskIdRequest, ProposalActionResponse>
    @JvmStatic
    get() = OrchestratorProposalServiceGrpc.getSendForApprovalMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.orchestrator.OrchestratorProposalService service as suspending coroutines.
   */
  @StubFor(OrchestratorProposalServiceGrpc::class)
  public class OrchestratorProposalServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<OrchestratorProposalServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): OrchestratorProposalServiceCoroutineStub = OrchestratorProposalServiceCoroutineStub(channel, callOptions)

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
    public suspend fun proposeTask(request: ProposeTaskRequest, headers: Metadata = Metadata()): ProposeTaskResponse = unaryRpc(
      channel,
      OrchestratorProposalServiceGrpc.getProposeTaskMethod(),
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
    public suspend fun updateProposedTask(request: UpdateProposedTaskRequest, headers: Metadata = Metadata()): UpdateProposedTaskResponse = unaryRpc(
      channel,
      OrchestratorProposalServiceGrpc.getUpdateProposedTaskMethod(),
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
    public suspend fun sendForApproval(request: TaskIdRequest, headers: Metadata = Metadata()): ProposalActionResponse = unaryRpc(
      channel,
      OrchestratorProposalServiceGrpc.getSendForApprovalMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.orchestrator.OrchestratorProposalService service based on Kotlin coroutines.
   */
  public abstract class OrchestratorProposalServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.orchestrator.OrchestratorProposalService.ProposeTask.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun proposeTask(request: ProposeTaskRequest): ProposeTaskResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorProposalService.ProposeTask is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.orchestrator.OrchestratorProposalService.UpdateProposedTask.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun updateProposedTask(request: UpdateProposedTaskRequest): UpdateProposedTaskResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorProposalService.UpdateProposedTask is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.orchestrator.OrchestratorProposalService.SendForApproval.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun sendForApproval(request: TaskIdRequest): ProposalActionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorProposalService.SendForApproval is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorProposalServiceGrpc.getProposeTaskMethod(),
      implementation = ::proposeTask
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorProposalServiceGrpc.getUpdateProposedTaskMethod(),
      implementation = ::updateProposedTask
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorProposalServiceGrpc.getSendForApprovalMethod(),
      implementation = ::sendForApproval
    )).build()
  }
}
