package com.jervis.contracts.correction

import com.jervis.contracts.correction.CorrectionServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.correction.CorrectionService.
 */
public object CorrectionServiceGrpcKt {
  public const val SERVICE_NAME: String = CorrectionServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val submitCorrectionMethod:
      MethodDescriptor<SubmitCorrectionRequest, SubmitCorrectionResponse>
    @JvmStatic
    get() = CorrectionServiceGrpc.getSubmitCorrectionMethod()

  public val correctTranscriptMethod: MethodDescriptor<CorrectTranscriptRequest, CorrectResult>
    @JvmStatic
    get() = CorrectionServiceGrpc.getCorrectTranscriptMethod()

  public val listCorrectionsMethod:
      MethodDescriptor<ListCorrectionsRequest, ListCorrectionsResponse>
    @JvmStatic
    get() = CorrectionServiceGrpc.getListCorrectionsMethod()

  public val answerCorrectionQuestionsMethod:
      MethodDescriptor<AnswerCorrectionsRequest, AnswerCorrectionsResponse>
    @JvmStatic
    get() = CorrectionServiceGrpc.getAnswerCorrectionQuestionsMethod()

  public val correctWithInstructionMethod:
      MethodDescriptor<CorrectWithInstructionRequest, CorrectWithInstructionResponse>
    @JvmStatic
    get() = CorrectionServiceGrpc.getCorrectWithInstructionMethod()

  public val correctTargetedMethod: MethodDescriptor<CorrectTargetedRequest, CorrectResult>
    @JvmStatic
    get() = CorrectionServiceGrpc.getCorrectTargetedMethod()

  public val deleteCorrectionMethod:
      MethodDescriptor<DeleteCorrectionRequest, DeleteCorrectionResponse>
    @JvmStatic
    get() = CorrectionServiceGrpc.getDeleteCorrectionMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.correction.CorrectionService service as suspending coroutines.
   */
  @StubFor(CorrectionServiceGrpc::class)
  public class CorrectionServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<CorrectionServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): CorrectionServiceCoroutineStub = CorrectionServiceCoroutineStub(channel, callOptions)

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
    public suspend fun submitCorrection(request: SubmitCorrectionRequest, headers: Metadata = Metadata()): SubmitCorrectionResponse = unaryRpc(
      channel,
      CorrectionServiceGrpc.getSubmitCorrectionMethod(),
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
    public suspend fun correctTranscript(request: CorrectTranscriptRequest, headers: Metadata = Metadata()): CorrectResult = unaryRpc(
      channel,
      CorrectionServiceGrpc.getCorrectTranscriptMethod(),
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
    public suspend fun listCorrections(request: ListCorrectionsRequest, headers: Metadata = Metadata()): ListCorrectionsResponse = unaryRpc(
      channel,
      CorrectionServiceGrpc.getListCorrectionsMethod(),
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
    public suspend fun answerCorrectionQuestions(request: AnswerCorrectionsRequest, headers: Metadata = Metadata()): AnswerCorrectionsResponse = unaryRpc(
      channel,
      CorrectionServiceGrpc.getAnswerCorrectionQuestionsMethod(),
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
    public suspend fun correctWithInstruction(request: CorrectWithInstructionRequest, headers: Metadata = Metadata()): CorrectWithInstructionResponse = unaryRpc(
      channel,
      CorrectionServiceGrpc.getCorrectWithInstructionMethod(),
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
    public suspend fun correctTargeted(request: CorrectTargetedRequest, headers: Metadata = Metadata()): CorrectResult = unaryRpc(
      channel,
      CorrectionServiceGrpc.getCorrectTargetedMethod(),
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
    public suspend fun deleteCorrection(request: DeleteCorrectionRequest, headers: Metadata = Metadata()): DeleteCorrectionResponse = unaryRpc(
      channel,
      CorrectionServiceGrpc.getDeleteCorrectionMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.correction.CorrectionService service based on Kotlin coroutines.
   */
  public abstract class CorrectionServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.correction.CorrectionService.SubmitCorrection.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun submitCorrection(request: SubmitCorrectionRequest): SubmitCorrectionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.correction.CorrectionService.SubmitCorrection is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.correction.CorrectionService.CorrectTranscript.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun correctTranscript(request: CorrectTranscriptRequest): CorrectResult = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.correction.CorrectionService.CorrectTranscript is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.correction.CorrectionService.ListCorrections.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listCorrections(request: ListCorrectionsRequest): ListCorrectionsResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.correction.CorrectionService.ListCorrections is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.correction.CorrectionService.AnswerCorrectionQuestions.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun answerCorrectionQuestions(request: AnswerCorrectionsRequest): AnswerCorrectionsResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.correction.CorrectionService.AnswerCorrectionQuestions is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.correction.CorrectionService.CorrectWithInstruction.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun correctWithInstruction(request: CorrectWithInstructionRequest): CorrectWithInstructionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.correction.CorrectionService.CorrectWithInstruction is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.correction.CorrectionService.CorrectTargeted.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun correctTargeted(request: CorrectTargetedRequest): CorrectResult = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.correction.CorrectionService.CorrectTargeted is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.correction.CorrectionService.DeleteCorrection.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun deleteCorrection(request: DeleteCorrectionRequest): DeleteCorrectionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.correction.CorrectionService.DeleteCorrection is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = CorrectionServiceGrpc.getSubmitCorrectionMethod(),
      implementation = ::submitCorrection
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = CorrectionServiceGrpc.getCorrectTranscriptMethod(),
      implementation = ::correctTranscript
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = CorrectionServiceGrpc.getListCorrectionsMethod(),
      implementation = ::listCorrections
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = CorrectionServiceGrpc.getAnswerCorrectionQuestionsMethod(),
      implementation = ::answerCorrectionQuestions
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = CorrectionServiceGrpc.getCorrectWithInstructionMethod(),
      implementation = ::correctWithInstruction
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = CorrectionServiceGrpc.getCorrectTargetedMethod(),
      implementation = ::correctTargeted
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = CorrectionServiceGrpc.getDeleteCorrectionMethod(),
      implementation = ::deleteCorrection
    )).build()
  }
}
