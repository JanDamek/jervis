package com.jervis.contracts.server

import com.jervis.contracts.server.ServerOrchestratorProgressServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerOrchestratorProgressService.
 */
public object ServerOrchestratorProgressServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerOrchestratorProgressServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val orchestratorProgressMethod: MethodDescriptor<OrchestratorProgressRequest, AckResponse>
    @JvmStatic
    get() = ServerOrchestratorProgressServiceGrpc.getOrchestratorProgressMethod()

  public val orchestratorStatusMethod: MethodDescriptor<OrchestratorStatusRequest, AckResponse>
    @JvmStatic
    get() = ServerOrchestratorProgressServiceGrpc.getOrchestratorStatusMethod()

  public val qualificationDoneMethod: MethodDescriptor<QualificationDoneRequest, AckResponse>
    @JvmStatic
    get() = ServerOrchestratorProgressServiceGrpc.getQualificationDoneMethod()

  public val memoryGraphChangedMethod: MethodDescriptor<MemoryGraphChangedRequest, AckResponse>
    @JvmStatic
    get() = ServerOrchestratorProgressServiceGrpc.getMemoryGraphChangedMethod()

  public val thinkingGraphUpdateMethod: MethodDescriptor<ThinkingGraphUpdateRequest, AckResponse>
    @JvmStatic
    get() = ServerOrchestratorProgressServiceGrpc.getThinkingGraphUpdateMethod()

  public val correctionProgressMethod: MethodDescriptor<CorrectionProgressRequest, AckResponse>
    @JvmStatic
    get() = ServerOrchestratorProgressServiceGrpc.getCorrectionProgressMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerOrchestratorProgressService service as suspending coroutines.
   */
  @StubFor(ServerOrchestratorProgressServiceGrpc::class)
  public class ServerOrchestratorProgressServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerOrchestratorProgressServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerOrchestratorProgressServiceCoroutineStub = ServerOrchestratorProgressServiceCoroutineStub(channel, callOptions)

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
    public suspend fun orchestratorProgress(request: OrchestratorProgressRequest, headers: Metadata = Metadata()): AckResponse = unaryRpc(
      channel,
      ServerOrchestratorProgressServiceGrpc.getOrchestratorProgressMethod(),
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
    public suspend fun orchestratorStatus(request: OrchestratorStatusRequest, headers: Metadata = Metadata()): AckResponse = unaryRpc(
      channel,
      ServerOrchestratorProgressServiceGrpc.getOrchestratorStatusMethod(),
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
    public suspend fun qualificationDone(request: QualificationDoneRequest, headers: Metadata = Metadata()): AckResponse = unaryRpc(
      channel,
      ServerOrchestratorProgressServiceGrpc.getQualificationDoneMethod(),
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
    public suspend fun memoryGraphChanged(request: MemoryGraphChangedRequest, headers: Metadata = Metadata()): AckResponse = unaryRpc(
      channel,
      ServerOrchestratorProgressServiceGrpc.getMemoryGraphChangedMethod(),
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
    public suspend fun thinkingGraphUpdate(request: ThinkingGraphUpdateRequest, headers: Metadata = Metadata()): AckResponse = unaryRpc(
      channel,
      ServerOrchestratorProgressServiceGrpc.getThinkingGraphUpdateMethod(),
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
    public suspend fun correctionProgress(request: CorrectionProgressRequest, headers: Metadata = Metadata()): AckResponse = unaryRpc(
      channel,
      ServerOrchestratorProgressServiceGrpc.getCorrectionProgressMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerOrchestratorProgressService service based on Kotlin coroutines.
   */
  public abstract class ServerOrchestratorProgressServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerOrchestratorProgressService.OrchestratorProgress.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun orchestratorProgress(request: OrchestratorProgressRequest): AckResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerOrchestratorProgressService.OrchestratorProgress is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerOrchestratorProgressService.OrchestratorStatus.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun orchestratorStatus(request: OrchestratorStatusRequest): AckResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerOrchestratorProgressService.OrchestratorStatus is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerOrchestratorProgressService.QualificationDone.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun qualificationDone(request: QualificationDoneRequest): AckResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerOrchestratorProgressService.QualificationDone is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerOrchestratorProgressService.MemoryGraphChanged.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun memoryGraphChanged(request: MemoryGraphChangedRequest): AckResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerOrchestratorProgressService.MemoryGraphChanged is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerOrchestratorProgressService.ThinkingGraphUpdate.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun thinkingGraphUpdate(request: ThinkingGraphUpdateRequest): AckResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerOrchestratorProgressService.ThinkingGraphUpdate is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerOrchestratorProgressService.CorrectionProgress.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun correctionProgress(request: CorrectionProgressRequest): AckResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerOrchestratorProgressService.CorrectionProgress is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerOrchestratorProgressServiceGrpc.getOrchestratorProgressMethod(),
      implementation = ::orchestratorProgress
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerOrchestratorProgressServiceGrpc.getOrchestratorStatusMethod(),
      implementation = ::orchestratorStatus
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerOrchestratorProgressServiceGrpc.getQualificationDoneMethod(),
      implementation = ::qualificationDone
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerOrchestratorProgressServiceGrpc.getMemoryGraphChangedMethod(),
      implementation = ::memoryGraphChanged
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerOrchestratorProgressServiceGrpc.getThinkingGraphUpdateMethod(),
      implementation = ::thinkingGraphUpdate
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerOrchestratorProgressServiceGrpc.getCorrectionProgressMethod(),
      implementation = ::correctionProgress
    )).build()
  }
}
