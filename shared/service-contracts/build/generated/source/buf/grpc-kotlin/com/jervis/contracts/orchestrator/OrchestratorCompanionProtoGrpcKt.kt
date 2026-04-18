package com.jervis.contracts.orchestrator

import com.jervis.contracts.orchestrator.OrchestratorCompanionServiceGrpc.getServiceDescriptor
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
import io.grpc.kotlin.ClientCalls.serverStreamingRpc
import io.grpc.kotlin.ClientCalls.unaryRpc
import io.grpc.kotlin.ServerCalls.serverStreamingServerMethodDefinition
import io.grpc.kotlin.ServerCalls.unaryServerMethodDefinition
import io.grpc.kotlin.StubFor
import kotlin.String
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlinx.coroutines.flow.Flow

/**
 * Holder for Kotlin coroutine-based client and server APIs for
 * jervis.orchestrator.OrchestratorCompanionService.
 */
public object OrchestratorCompanionServiceGrpcKt {
  public const val SERVICE_NAME: String = OrchestratorCompanionServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val startSessionMethod: MethodDescriptor<SessionStartRequest, SessionStartResponse>
    @JvmStatic
    get() = OrchestratorCompanionServiceGrpc.getStartSessionMethod()

  public val sessionEventMethod: MethodDescriptor<SessionEventRequest, SessionEventAck>
    @JvmStatic
    get() = OrchestratorCompanionServiceGrpc.getSessionEventMethod()

  public val stopSessionMethod: MethodDescriptor<SessionRef, SessionAck>
    @JvmStatic
    get() = OrchestratorCompanionServiceGrpc.getStopSessionMethod()

  public val streamSessionMethod: MethodDescriptor<StreamSessionRequest, OutboxEvent>
    @JvmStatic
    get() = OrchestratorCompanionServiceGrpc.getStreamSessionMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.orchestrator.OrchestratorCompanionService service as
   * suspending coroutines.
   */
  @StubFor(OrchestratorCompanionServiceGrpc::class)
  public class OrchestratorCompanionServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<OrchestratorCompanionServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions):
        OrchestratorCompanionServiceCoroutineStub =
        OrchestratorCompanionServiceCoroutineStub(channel, callOptions)

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
    public suspend fun startSession(request: SessionStartRequest, headers: Metadata = Metadata()):
        SessionStartResponse = unaryRpc(
      channel,
      OrchestratorCompanionServiceGrpc.getStartSessionMethod(),
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
    public suspend fun sessionEvent(request: SessionEventRequest, headers: Metadata = Metadata()):
        SessionEventAck = unaryRpc(
      channel,
      OrchestratorCompanionServiceGrpc.getSessionEventMethod(),
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
    public suspend fun stopSession(request: SessionRef, headers: Metadata = Metadata()): SessionAck
        = unaryRpc(
      channel,
      OrchestratorCompanionServiceGrpc.getStopSessionMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Returns a [Flow] that, when collected, executes this RPC and emits responses from the
     * server as they arrive.  That flow finishes normally if the server closes its response with
     * [`Status.OK`][io.grpc.Status], and fails by throwing a [StatusException] otherwise.  If
     * collecting the flow downstream fails exceptionally (including via cancellation), the RPC
     * is cancelled with that exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return A flow that, when collected, emits the responses from the server.
     */
    public fun streamSession(request: StreamSessionRequest, headers: Metadata = Metadata()):
        Flow<OutboxEvent> = serverStreamingRpc(
      channel,
      OrchestratorCompanionServiceGrpc.getStreamSessionMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.orchestrator.OrchestratorCompanionService service based
   * on Kotlin coroutines.
   */
  public abstract class OrchestratorCompanionServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for
     * jervis.orchestrator.OrchestratorCompanionService.StartSession.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun startSession(request: SessionStartRequest): SessionStartResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorCompanionService.StartSession is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.orchestrator.OrchestratorCompanionService.SessionEvent.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun sessionEvent(request: SessionEventRequest): SessionEventAck = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorCompanionService.SessionEvent is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.orchestrator.OrchestratorCompanionService.StopSession.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun stopSession(request: SessionRef): SessionAck = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorCompanionService.StopSession is unimplemented"))

    /**
     * Returns a [Flow] of responses to an RPC for
     * jervis.orchestrator.OrchestratorCompanionService.StreamSession.
     *
     * If creating or collecting the returned flow fails with a [StatusException], the RPC
     * will fail with the corresponding [io.grpc.Status].  If it fails with a
     * [java.util.concurrent.CancellationException], the RPC will fail with status
     * `Status.CANCELLED`.  If creating
     * or collecting the returned flow fails for any other reason, the RPC will fail with
     * `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open fun streamSession(request: StreamSessionRequest): Flow<OutboxEvent> = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.orchestrator.OrchestratorCompanionService.StreamSession is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorCompanionServiceGrpc.getStartSessionMethod(),
      implementation = ::startSession
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorCompanionServiceGrpc.getSessionEventMethod(),
      implementation = ::sessionEvent
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorCompanionServiceGrpc.getStopSessionMethod(),
      implementation = ::stopSession
    ))
      .addMethod(serverStreamingServerMethodDefinition(
      context = this.context,
      descriptor = OrchestratorCompanionServiceGrpc.getStreamSessionMethod(),
      implementation = ::streamSession
    )).build()
  }
}
