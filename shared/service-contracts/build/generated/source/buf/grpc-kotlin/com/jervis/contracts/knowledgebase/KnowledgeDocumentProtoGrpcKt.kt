package com.jervis.contracts.knowledgebase

import com.jervis.contracts.knowledgebase.KnowledgeDocumentServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.knowledgebase.KnowledgeDocumentService.
 */
public object KnowledgeDocumentServiceGrpcKt {
  public const val SERVICE_NAME: String = KnowledgeDocumentServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val uploadMethod: MethodDescriptor<DocumentUploadRequest, Document>
    @JvmStatic
    get() = KnowledgeDocumentServiceGrpc.getUploadMethod()

  public val registerMethod: MethodDescriptor<DocumentRegisterRequest, Document>
    @JvmStatic
    get() = KnowledgeDocumentServiceGrpc.getRegisterMethod()

  public val listMethod: MethodDescriptor<DocumentListRequest, DocumentList>
    @JvmStatic
    get() = KnowledgeDocumentServiceGrpc.getListMethod()

  public val getMethod: MethodDescriptor<DocumentId, Document>
    @JvmStatic
    get() = KnowledgeDocumentServiceGrpc.getGetMethod()

  public val updateMethod: MethodDescriptor<DocumentUpdateRequest, Document>
    @JvmStatic
    get() = KnowledgeDocumentServiceGrpc.getUpdateMethod()

  public val deleteMethod: MethodDescriptor<DocumentId, DocumentAck>
    @JvmStatic
    get() = KnowledgeDocumentServiceGrpc.getDeleteMethod()

  public val reindexMethod: MethodDescriptor<DocumentId, DocumentAck>
    @JvmStatic
    get() = KnowledgeDocumentServiceGrpc.getReindexMethod()

  public val extractTextMethod: MethodDescriptor<DocumentExtractRequest, DocumentExtractResult>
    @JvmStatic
    get() = KnowledgeDocumentServiceGrpc.getExtractTextMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.knowledgebase.KnowledgeDocumentService service as suspending coroutines.
   */
  @StubFor(KnowledgeDocumentServiceGrpc::class)
  public class KnowledgeDocumentServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<KnowledgeDocumentServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): KnowledgeDocumentServiceCoroutineStub = KnowledgeDocumentServiceCoroutineStub(channel, callOptions)

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
    public suspend fun upload(request: DocumentUploadRequest, headers: Metadata = Metadata()): Document = unaryRpc(
      channel,
      KnowledgeDocumentServiceGrpc.getUploadMethod(),
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
    public suspend fun register(request: DocumentRegisterRequest, headers: Metadata = Metadata()): Document = unaryRpc(
      channel,
      KnowledgeDocumentServiceGrpc.getRegisterMethod(),
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
    public suspend fun list(request: DocumentListRequest, headers: Metadata = Metadata()): DocumentList = unaryRpc(
      channel,
      KnowledgeDocumentServiceGrpc.getListMethod(),
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
    public suspend fun `get`(request: DocumentId, headers: Metadata = Metadata()): Document = unaryRpc(
      channel,
      KnowledgeDocumentServiceGrpc.getGetMethod(),
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
    public suspend fun update(request: DocumentUpdateRequest, headers: Metadata = Metadata()): Document = unaryRpc(
      channel,
      KnowledgeDocumentServiceGrpc.getUpdateMethod(),
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
    public suspend fun delete(request: DocumentId, headers: Metadata = Metadata()): DocumentAck = unaryRpc(
      channel,
      KnowledgeDocumentServiceGrpc.getDeleteMethod(),
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
    public suspend fun reindex(request: DocumentId, headers: Metadata = Metadata()): DocumentAck = unaryRpc(
      channel,
      KnowledgeDocumentServiceGrpc.getReindexMethod(),
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
    public suspend fun extractText(request: DocumentExtractRequest, headers: Metadata = Metadata()): DocumentExtractResult = unaryRpc(
      channel,
      KnowledgeDocumentServiceGrpc.getExtractTextMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.knowledgebase.KnowledgeDocumentService service based on Kotlin coroutines.
   */
  public abstract class KnowledgeDocumentServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeDocumentService.Upload.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun upload(request: DocumentUploadRequest): Document = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeDocumentService.Upload is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeDocumentService.Register.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun register(request: DocumentRegisterRequest): Document = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeDocumentService.Register is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeDocumentService.List.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun list(request: DocumentListRequest): DocumentList = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeDocumentService.List is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeDocumentService.Get.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun `get`(request: DocumentId): Document = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeDocumentService.Get is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeDocumentService.Update.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun update(request: DocumentUpdateRequest): Document = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeDocumentService.Update is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeDocumentService.Delete.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun delete(request: DocumentId): DocumentAck = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeDocumentService.Delete is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeDocumentService.Reindex.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun reindex(request: DocumentId): DocumentAck = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeDocumentService.Reindex is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeDocumentService.ExtractText.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun extractText(request: DocumentExtractRequest): DocumentExtractResult = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeDocumentService.ExtractText is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeDocumentServiceGrpc.getUploadMethod(),
      implementation = ::upload
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeDocumentServiceGrpc.getRegisterMethod(),
      implementation = ::register
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeDocumentServiceGrpc.getListMethod(),
      implementation = ::list
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeDocumentServiceGrpc.getGetMethod(),
      implementation = ::`get`
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeDocumentServiceGrpc.getUpdateMethod(),
      implementation = ::update
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeDocumentServiceGrpc.getDeleteMethod(),
      implementation = ::delete
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeDocumentServiceGrpc.getReindexMethod(),
      implementation = ::reindex
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeDocumentServiceGrpc.getExtractTextMethod(),
      implementation = ::extractText
    )).build()
  }
}
