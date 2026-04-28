package com.jervis.contracts.server

import com.jervis.contracts.server.ServerTaskProposalServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerTaskProposalService.
 */
public object ServerTaskProposalServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerTaskProposalServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val insertProposalMethod: MethodDescriptor<InsertProposalRequest, InsertProposalResponse>
    @JvmStatic
    get() = ServerTaskProposalServiceGrpc.getInsertProposalMethod()

  public val updateProposalMethod: MethodDescriptor<UpdateProposalRequest, UpdateProposalResponse>
    @JvmStatic
    get() = ServerTaskProposalServiceGrpc.getUpdateProposalMethod()

  public val sendForApprovalMethod: MethodDescriptor<TaskIdRequest, ProposalActionResponse>
    @JvmStatic
    get() = ServerTaskProposalServiceGrpc.getSendForApprovalMethod()

  public val approveTaskMethod: MethodDescriptor<TaskIdRequest, ProposalActionResponse>
    @JvmStatic
    get() = ServerTaskProposalServiceGrpc.getApproveTaskMethod()

  public val rejectTaskMethod: MethodDescriptor<RejectTaskRequest, ProposalActionResponse>
    @JvmStatic
    get() = ServerTaskProposalServiceGrpc.getRejectTaskMethod()

  public val listPendingProposalsForDedupMethod: MethodDescriptor<DedupRequest, DedupResponse>
    @JvmStatic
    get() = ServerTaskProposalServiceGrpc.getListPendingProposalsForDedupMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerTaskProposalService service as suspending coroutines.
   */
  @StubFor(ServerTaskProposalServiceGrpc::class)
  public class ServerTaskProposalServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerTaskProposalServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerTaskProposalServiceCoroutineStub = ServerTaskProposalServiceCoroutineStub(channel, callOptions)

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
    public suspend fun insertProposal(request: InsertProposalRequest, headers: Metadata = Metadata()): InsertProposalResponse = unaryRpc(
      channel,
      ServerTaskProposalServiceGrpc.getInsertProposalMethod(),
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
    public suspend fun updateProposal(request: UpdateProposalRequest, headers: Metadata = Metadata()): UpdateProposalResponse = unaryRpc(
      channel,
      ServerTaskProposalServiceGrpc.getUpdateProposalMethod(),
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
      ServerTaskProposalServiceGrpc.getSendForApprovalMethod(),
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
    public suspend fun approveTask(request: TaskIdRequest, headers: Metadata = Metadata()): ProposalActionResponse = unaryRpc(
      channel,
      ServerTaskProposalServiceGrpc.getApproveTaskMethod(),
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
    public suspend fun rejectTask(request: RejectTaskRequest, headers: Metadata = Metadata()): ProposalActionResponse = unaryRpc(
      channel,
      ServerTaskProposalServiceGrpc.getRejectTaskMethod(),
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
    public suspend fun listPendingProposalsForDedup(request: DedupRequest, headers: Metadata = Metadata()): DedupResponse = unaryRpc(
      channel,
      ServerTaskProposalServiceGrpc.getListPendingProposalsForDedupMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerTaskProposalService service based on Kotlin coroutines.
   */
  public abstract class ServerTaskProposalServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerTaskProposalService.InsertProposal.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun insertProposal(request: InsertProposalRequest): InsertProposalResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskProposalService.InsertProposal is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskProposalService.UpdateProposal.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun updateProposal(request: UpdateProposalRequest): UpdateProposalResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskProposalService.UpdateProposal is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskProposalService.SendForApproval.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun sendForApproval(request: TaskIdRequest): ProposalActionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskProposalService.SendForApproval is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskProposalService.ApproveTask.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun approveTask(request: TaskIdRequest): ProposalActionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskProposalService.ApproveTask is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskProposalService.RejectTask.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun rejectTask(request: RejectTaskRequest): ProposalActionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskProposalService.RejectTask is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTaskProposalService.ListPendingProposalsForDedup.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listPendingProposalsForDedup(request: DedupRequest): DedupResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTaskProposalService.ListPendingProposalsForDedup is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskProposalServiceGrpc.getInsertProposalMethod(),
      implementation = ::insertProposal
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskProposalServiceGrpc.getUpdateProposalMethod(),
      implementation = ::updateProposal
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskProposalServiceGrpc.getSendForApprovalMethod(),
      implementation = ::sendForApproval
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskProposalServiceGrpc.getApproveTaskMethod(),
      implementation = ::approveTask
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskProposalServiceGrpc.getRejectTaskMethod(),
      implementation = ::rejectTask
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTaskProposalServiceGrpc.getListPendingProposalsForDedupMethod(),
      implementation = ::listPendingProposalsForDedup
    )).build()
  }
}
