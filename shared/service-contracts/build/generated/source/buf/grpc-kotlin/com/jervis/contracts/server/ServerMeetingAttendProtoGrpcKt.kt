package com.jervis.contracts.server

import com.jervis.contracts.server.ServerMeetingAttendServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerMeetingAttendService.
 */
public object ServerMeetingAttendServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerMeetingAttendServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val listUpcomingMethod: MethodDescriptor<ListUpcomingRequest, ListUpcomingResponse>
    @JvmStatic
    get() = ServerMeetingAttendServiceGrpc.getListUpcomingMethod()

  public val approveMethod: MethodDescriptor<AttendDecisionRequest, AttendDecisionResponse>
    @JvmStatic
    get() = ServerMeetingAttendServiceGrpc.getApproveMethod()

  public val denyMethod: MethodDescriptor<AttendDecisionRequest, AttendDecisionResponse>
    @JvmStatic
    get() = ServerMeetingAttendServiceGrpc.getDenyMethod()

  public val reportPresenceMethod: MethodDescriptor<PresenceRequest, PresenceResponse>
    @JvmStatic
    get() = ServerMeetingAttendServiceGrpc.getReportPresenceMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerMeetingAttendService service as suspending coroutines.
   */
  @StubFor(ServerMeetingAttendServiceGrpc::class)
  public class ServerMeetingAttendServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerMeetingAttendServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerMeetingAttendServiceCoroutineStub = ServerMeetingAttendServiceCoroutineStub(channel, callOptions)

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
    public suspend fun listUpcoming(request: ListUpcomingRequest, headers: Metadata = Metadata()): ListUpcomingResponse = unaryRpc(
      channel,
      ServerMeetingAttendServiceGrpc.getListUpcomingMethod(),
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
    public suspend fun approve(request: AttendDecisionRequest, headers: Metadata = Metadata()): AttendDecisionResponse = unaryRpc(
      channel,
      ServerMeetingAttendServiceGrpc.getApproveMethod(),
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
    public suspend fun deny(request: AttendDecisionRequest, headers: Metadata = Metadata()): AttendDecisionResponse = unaryRpc(
      channel,
      ServerMeetingAttendServiceGrpc.getDenyMethod(),
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
    public suspend fun reportPresence(request: PresenceRequest, headers: Metadata = Metadata()): PresenceResponse = unaryRpc(
      channel,
      ServerMeetingAttendServiceGrpc.getReportPresenceMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerMeetingAttendService service based on Kotlin coroutines.
   */
  public abstract class ServerMeetingAttendServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerMeetingAttendService.ListUpcoming.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listUpcoming(request: ListUpcomingRequest): ListUpcomingResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerMeetingAttendService.ListUpcoming is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerMeetingAttendService.Approve.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun approve(request: AttendDecisionRequest): AttendDecisionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerMeetingAttendService.Approve is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerMeetingAttendService.Deny.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun deny(request: AttendDecisionRequest): AttendDecisionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerMeetingAttendService.Deny is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerMeetingAttendService.ReportPresence.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun reportPresence(request: PresenceRequest): PresenceResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerMeetingAttendService.ReportPresence is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerMeetingAttendServiceGrpc.getListUpcomingMethod(),
      implementation = ::listUpcoming
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerMeetingAttendServiceGrpc.getApproveMethod(),
      implementation = ::approve
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerMeetingAttendServiceGrpc.getDenyMethod(),
      implementation = ::deny
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerMeetingAttendServiceGrpc.getReportPresenceMethod(),
      implementation = ::reportPresence
    )).build()
  }
}
