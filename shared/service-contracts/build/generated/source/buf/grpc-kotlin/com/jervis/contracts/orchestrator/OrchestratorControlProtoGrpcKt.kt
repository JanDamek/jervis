package com.jervis.contracts.orchestrator

import com.jervis.contracts.orchestrator.OrchestratorControlServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.orchestrator.OrchestratorControlService.
 */
public object OrchestratorControlServiceGrpcKt {
  public const val SERVICE_NAME: String = OrchestratorControlServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val healthMethod: MethodDescriptor<HealthRequest, HealthResponse>
    @JvmStatic
    get() = OrchestratorControlServiceGrpc.getHealthMethod()

  public val getStatusMethod: MethodDescriptor<StatusRequest, StatusResponse>
    @JvmStatic
    get() = OrchestratorControlServiceGrpc.getGetStatusMethod()

  public val approveMethod: MethodDescriptor<ApproveRequest, ApproveAck>
    @JvmStatic
    get() = OrchestratorControlServiceGrpc.getApproveMethod()

  public val cancelMethod: MethodDescriptor<ThreadRequest, CancelAck>
    @JvmStatic
    get() = OrchestratorControlServiceGrpc.getCancelMethod()

  public val interruptMethod: MethodDescriptor<ThreadRequest, InterruptAck>
    @JvmStatic
    get() = OrchestratorControlServiceGrpc.getInterruptMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.orchestrator.OrchestratorControlService service as suspending coroutines.
   */
  @StubFor(OrchestratorControlServiceGrpc::class)
  public class OrchestratorControlServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<OrchestratorControlServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): OrchestratorControlServiceCoroutineStub = OrchestratorControlServiceCoroutineStub(channel, callOptions)

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
      OrchestratorControlServiceGrpc.getHealthMethod(),
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
    public suspend fun getStatus(request: StatusRequest, headers: Metadata = Metadata()): StatusResponse = unaryRpc(
      channel,
      OrchestratorControlServiceGrpc.getGetStatusMethod(),
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
    public suspend fun approve(request: ApproveRequest, headers: Metadata = Metadata()): ApproveAck = unaryRpc(
      channel,
      OrchestratorControlServiceGrpc.getApproveMethod(),
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
    public suspend fun cancel(request: ThreadRequest, headers: Metadata = Metadata()): CancelAck = unaryRpc(
      channel,
      OrchestratorControlServiceGrpc.getCancelMethod(),
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
    public suspend fun interrupt(request: ThreadRequest, headers: Metadata = Metadata()): InterruptAck = unaryRpc(
      channel,
      OrchestratorControlServiceGrpc.getInterruptMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.orchestrator.OrchestratorControlService service based on Kotlin coroutines.
   */
  public abstract class OrchestratorControlServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.orchestrator.OrchestratorControlService.Health.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun health(request: HealthRequest): HealthResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorControlService.Health is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.orchestrator.OrchestratorControlService.GetStatus.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getStatus(request: StatusRequest): StatusResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorControlService.GetStatus is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.orchestrator.OrchestratorControlService.Approve.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun approve(request: ApproveRequest): ApproveAck = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorControlService.Approve is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.orchestrator.OrchestratorControlService.Cancel.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun cancel(request: ThreadRequest): CancelAck = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorControlService.Cancel is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.orchestrator.OrchestratorControlService.Interrupt.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun interrupt(request: ThreadRequest): InterruptAck = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorControlService.Interrupt is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorControlServiceGrpc.getHealthMethod(),
      implementation = ::health
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorControlServiceGrpc.getGetStatusMethod(),
      implementation = ::getStatus
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorControlServiceGrpc.getApproveMethod(),
      implementation = ::approve
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorControlServiceGrpc.getCancelMethod(),
      implementation = ::cancel
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorControlServiceGrpc.getInterruptMethod(),
      implementation = ::interrupt
    )).build()
  }
}
