package com.jervis.contracts.knowledgebase

import com.jervis.contracts.knowledgebase.KnowledgeRetrieveServiceGrpc.getServiceDescriptor
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
 * jervis.knowledgebase.KnowledgeRetrieveService.
 */
public object KnowledgeRetrieveServiceGrpcKt {
  public const val SERVICE_NAME: String = KnowledgeRetrieveServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val retrieveMethod: MethodDescriptor<RetrievalRequest, EvidencePack>
    @JvmStatic
    get() = KnowledgeRetrieveServiceGrpc.getRetrieveMethod()

  public val retrieveSimpleMethod: MethodDescriptor<RetrievalRequest, EvidencePack>
    @JvmStatic
    get() = KnowledgeRetrieveServiceGrpc.getRetrieveSimpleMethod()

  public val retrieveHybridMethod: MethodDescriptor<HybridRetrievalRequest, HybridEvidencePack>
    @JvmStatic
    get() = KnowledgeRetrieveServiceGrpc.getRetrieveHybridMethod()

  public val analyzeCodeMethod: MethodDescriptor<TraversalRequest, JoernAnalyzeResult>
    @JvmStatic
    get() = KnowledgeRetrieveServiceGrpc.getAnalyzeCodeMethod()

  public val joernScanMethod: MethodDescriptor<JoernScanRequest, JoernScanResult>
    @JvmStatic
    get() = KnowledgeRetrieveServiceGrpc.getJoernScanMethod()

  public val listChunksByKindMethod: MethodDescriptor<ListByKindRequest, ChunkList>
    @JvmStatic
    get() = KnowledgeRetrieveServiceGrpc.getListChunksByKindMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.knowledgebase.KnowledgeRetrieveService service as
   * suspending coroutines.
   */
  @StubFor(KnowledgeRetrieveServiceGrpc::class)
  public class KnowledgeRetrieveServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<KnowledgeRetrieveServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions):
        KnowledgeRetrieveServiceCoroutineStub = KnowledgeRetrieveServiceCoroutineStub(channel,
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
    public suspend fun retrieve(request: RetrievalRequest, headers: Metadata = Metadata()):
        EvidencePack = unaryRpc(
      channel,
      KnowledgeRetrieveServiceGrpc.getRetrieveMethod(),
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
    public suspend fun retrieveSimple(request: RetrievalRequest, headers: Metadata = Metadata()):
        EvidencePack = unaryRpc(
      channel,
      KnowledgeRetrieveServiceGrpc.getRetrieveSimpleMethod(),
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
    public suspend fun retrieveHybrid(request: HybridRetrievalRequest, headers: Metadata =
        Metadata()): HybridEvidencePack = unaryRpc(
      channel,
      KnowledgeRetrieveServiceGrpc.getRetrieveHybridMethod(),
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
    public suspend fun analyzeCode(request: TraversalRequest, headers: Metadata = Metadata()):
        JoernAnalyzeResult = unaryRpc(
      channel,
      KnowledgeRetrieveServiceGrpc.getAnalyzeCodeMethod(),
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
    public suspend fun joernScan(request: JoernScanRequest, headers: Metadata = Metadata()):
        JoernScanResult = unaryRpc(
      channel,
      KnowledgeRetrieveServiceGrpc.getJoernScanMethod(),
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
    public suspend fun listChunksByKind(request: ListByKindRequest, headers: Metadata = Metadata()):
        ChunkList = unaryRpc(
      channel,
      KnowledgeRetrieveServiceGrpc.getListChunksByKindMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.knowledgebase.KnowledgeRetrieveService service based on
   * Kotlin coroutines.
   */
  public abstract class KnowledgeRetrieveServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeRetrieveService.Retrieve.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun retrieve(request: RetrievalRequest): EvidencePack = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeRetrieveService.Retrieve is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.knowledgebase.KnowledgeRetrieveService.RetrieveSimple.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun retrieveSimple(request: RetrievalRequest): EvidencePack = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeRetrieveService.RetrieveSimple is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.knowledgebase.KnowledgeRetrieveService.RetrieveHybrid.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun retrieveHybrid(request: HybridRetrievalRequest): HybridEvidencePack =
        throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeRetrieveService.RetrieveHybrid is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeRetrieveService.AnalyzeCode.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun analyzeCode(request: TraversalRequest): JoernAnalyzeResult = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeRetrieveService.AnalyzeCode is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeRetrieveService.JoernScan.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun joernScan(request: JoernScanRequest): JoernScanResult = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeRetrieveService.JoernScan is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.knowledgebase.KnowledgeRetrieveService.ListChunksByKind.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listChunksByKind(request: ListByKindRequest): ChunkList = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeRetrieveService.ListChunksByKind is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeRetrieveServiceGrpc.getRetrieveMethod(),
      implementation = ::retrieve
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeRetrieveServiceGrpc.getRetrieveSimpleMethod(),
      implementation = ::retrieveSimple
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeRetrieveServiceGrpc.getRetrieveHybridMethod(),
      implementation = ::retrieveHybrid
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeRetrieveServiceGrpc.getAnalyzeCodeMethod(),
      implementation = ::analyzeCode
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeRetrieveServiceGrpc.getJoernScanMethod(),
      implementation = ::joernScan
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeRetrieveServiceGrpc.getListChunksByKindMethod(),
      implementation = ::listChunksByKind
    )).build()
  }
}
