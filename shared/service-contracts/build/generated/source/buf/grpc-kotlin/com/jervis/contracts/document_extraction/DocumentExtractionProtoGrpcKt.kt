package com.jervis.contracts.document_extraction

import com.jervis.contracts.document_extraction.DocumentExtractionServiceGrpc.getServiceDescriptor
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
 * jervis.document_extraction.DocumentExtractionService.
 */
public object DocumentExtractionServiceGrpcKt {
  public const val SERVICE_NAME: String = DocumentExtractionServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val extractMethod: MethodDescriptor<ExtractRequest, ExtractResponse>
    @JvmStatic
    get() = DocumentExtractionServiceGrpc.getExtractMethod()

  public val healthMethod: MethodDescriptor<HealthRequest, HealthResponse>
    @JvmStatic
    get() = DocumentExtractionServiceGrpc.getHealthMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.document_extraction.DocumentExtractionService service as
   * suspending coroutines.
   */
  @StubFor(DocumentExtractionServiceGrpc::class)
  public class DocumentExtractionServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<DocumentExtractionServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions):
        DocumentExtractionServiceCoroutineStub = DocumentExtractionServiceCoroutineStub(channel,
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
    public suspend fun extract(request: ExtractRequest, headers: Metadata = Metadata()):
        ExtractResponse = unaryRpc(
      channel,
      DocumentExtractionServiceGrpc.getExtractMethod(),
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
    public suspend fun health(request: HealthRequest, headers: Metadata = Metadata()):
        HealthResponse = unaryRpc(
      channel,
      DocumentExtractionServiceGrpc.getHealthMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.document_extraction.DocumentExtractionService service
   * based on Kotlin coroutines.
   */
  public abstract class DocumentExtractionServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for
     * jervis.document_extraction.DocumentExtractionService.Extract.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun extract(request: ExtractRequest): ExtractResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.document_extraction.DocumentExtractionService.Extract is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.document_extraction.DocumentExtractionService.Health.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun health(request: HealthRequest): HealthResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.document_extraction.DocumentExtractionService.Health is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = DocumentExtractionServiceGrpc.getExtractMethod(),
      implementation = ::extract
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = DocumentExtractionServiceGrpc.getHealthMethod(),
      implementation = ::health
    )).build()
  }
}
