package com.jervis.contracts.meeting_attender

import com.jervis.contracts.meeting_attender.MeetingAttenderServiceGrpc.getServiceDescriptor
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
 * jervis.meeting_attender.MeetingAttenderService.
 */
public object MeetingAttenderServiceGrpcKt {
  public const val SERVICE_NAME: String = MeetingAttenderServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val attendMethod: MethodDescriptor<AttendRequest, AttendResponse>
    @JvmStatic
    get() = MeetingAttenderServiceGrpc.getAttendMethod()

  public val stopMethod: MethodDescriptor<StopRequest, StopResponse>
    @JvmStatic
    get() = MeetingAttenderServiceGrpc.getStopMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.meeting_attender.MeetingAttenderService service as
   * suspending coroutines.
   */
  @StubFor(MeetingAttenderServiceGrpc::class)
  public class MeetingAttenderServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<MeetingAttenderServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions):
        MeetingAttenderServiceCoroutineStub = MeetingAttenderServiceCoroutineStub(channel,
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
    public suspend fun attend(request: AttendRequest, headers: Metadata = Metadata()):
        AttendResponse = unaryRpc(
      channel,
      MeetingAttenderServiceGrpc.getAttendMethod(),
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
    public suspend fun stop(request: StopRequest, headers: Metadata = Metadata()): StopResponse =
        unaryRpc(
      channel,
      MeetingAttenderServiceGrpc.getStopMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.meeting_attender.MeetingAttenderService service based on
   * Kotlin coroutines.
   */
  public abstract class MeetingAttenderServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.meeting_attender.MeetingAttenderService.Attend.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun attend(request: AttendRequest): AttendResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.meeting_attender.MeetingAttenderService.Attend is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.meeting_attender.MeetingAttenderService.Stop.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun stop(request: StopRequest): StopResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.meeting_attender.MeetingAttenderService.Stop is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = MeetingAttenderServiceGrpc.getAttendMethod(),
      implementation = ::attend
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = MeetingAttenderServiceGrpc.getStopMethod(),
      implementation = ::stop
    )).build()
  }
}
