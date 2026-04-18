package com.jervis.contracts.knowledgebase

import com.jervis.contracts.knowledgebase.KnowledgeIngestServiceGrpc.getServiceDescriptor
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
 * jervis.knowledgebase.KnowledgeIngestService.
 */
public object KnowledgeIngestServiceGrpcKt {
  public const val SERVICE_NAME: String = KnowledgeIngestServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val ingestMethod: MethodDescriptor<IngestRequest, IngestResult>
    @JvmStatic
    get() = KnowledgeIngestServiceGrpc.getIngestMethod()

  public val ingestImmediateMethod: MethodDescriptor<IngestRequest, IngestResult>
    @JvmStatic
    get() = KnowledgeIngestServiceGrpc.getIngestImmediateMethod()

  public val ingestQueueMethod: MethodDescriptor<IngestRequest, IngestQueueAck>
    @JvmStatic
    get() = KnowledgeIngestServiceGrpc.getIngestQueueMethod()

  public val ingestFileMethod: MethodDescriptor<IngestFileRequest, IngestResult>
    @JvmStatic
    get() = KnowledgeIngestServiceGrpc.getIngestFileMethod()

  public val ingestFullMethod: MethodDescriptor<FullIngestRequest, FullIngestResult>
    @JvmStatic
    get() = KnowledgeIngestServiceGrpc.getIngestFullMethod()

  public val ingestFullAsyncMethod: MethodDescriptor<AsyncFullIngestRequest, AsyncIngestAck>
    @JvmStatic
    get() = KnowledgeIngestServiceGrpc.getIngestFullAsyncMethod()

  public val ingestGitStructureMethod:
      MethodDescriptor<GitStructureIngestRequest, GitStructureIngestResult>
    @JvmStatic
    get() = KnowledgeIngestServiceGrpc.getIngestGitStructureMethod()

  public val ingestGitCommitsMethod: MethodDescriptor<GitCommitIngestRequest, GitCommitIngestResult>
    @JvmStatic
    get() = KnowledgeIngestServiceGrpc.getIngestGitCommitsMethod()

  public val ingestCpgMethod: MethodDescriptor<CpgIngestRequest, CpgIngestResult>
    @JvmStatic
    get() = KnowledgeIngestServiceGrpc.getIngestCpgMethod()

  public val crawlMethod: MethodDescriptor<CrawlRequest, IngestResult>
    @JvmStatic
    get() = KnowledgeIngestServiceGrpc.getCrawlMethod()

  public val purgeMethod: MethodDescriptor<PurgeRequest, PurgeResult>
    @JvmStatic
    get() = KnowledgeIngestServiceGrpc.getPurgeMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.knowledgebase.KnowledgeIngestService service as
   * suspending coroutines.
   */
  @StubFor(KnowledgeIngestServiceGrpc::class)
  public class KnowledgeIngestServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<KnowledgeIngestServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions):
        KnowledgeIngestServiceCoroutineStub = KnowledgeIngestServiceCoroutineStub(channel,
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
    public suspend fun ingest(request: IngestRequest, headers: Metadata = Metadata()): IngestResult
        = unaryRpc(
      channel,
      KnowledgeIngestServiceGrpc.getIngestMethod(),
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
    public suspend fun ingestImmediate(request: IngestRequest, headers: Metadata = Metadata()):
        IngestResult = unaryRpc(
      channel,
      KnowledgeIngestServiceGrpc.getIngestImmediateMethod(),
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
    public suspend fun ingestQueue(request: IngestRequest, headers: Metadata = Metadata()):
        IngestQueueAck = unaryRpc(
      channel,
      KnowledgeIngestServiceGrpc.getIngestQueueMethod(),
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
    public suspend fun ingestFile(request: IngestFileRequest, headers: Metadata = Metadata()):
        IngestResult = unaryRpc(
      channel,
      KnowledgeIngestServiceGrpc.getIngestFileMethod(),
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
    public suspend fun ingestFull(request: FullIngestRequest, headers: Metadata = Metadata()):
        FullIngestResult = unaryRpc(
      channel,
      KnowledgeIngestServiceGrpc.getIngestFullMethod(),
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
    public suspend fun ingestFullAsync(request: AsyncFullIngestRequest, headers: Metadata =
        Metadata()): AsyncIngestAck = unaryRpc(
      channel,
      KnowledgeIngestServiceGrpc.getIngestFullAsyncMethod(),
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
    public suspend fun ingestGitStructure(request: GitStructureIngestRequest, headers: Metadata =
        Metadata()): GitStructureIngestResult = unaryRpc(
      channel,
      KnowledgeIngestServiceGrpc.getIngestGitStructureMethod(),
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
    public suspend fun ingestGitCommits(request: GitCommitIngestRequest, headers: Metadata =
        Metadata()): GitCommitIngestResult = unaryRpc(
      channel,
      KnowledgeIngestServiceGrpc.getIngestGitCommitsMethod(),
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
    public suspend fun ingestCpg(request: CpgIngestRequest, headers: Metadata = Metadata()):
        CpgIngestResult = unaryRpc(
      channel,
      KnowledgeIngestServiceGrpc.getIngestCpgMethod(),
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
    public suspend fun crawl(request: CrawlRequest, headers: Metadata = Metadata()): IngestResult =
        unaryRpc(
      channel,
      KnowledgeIngestServiceGrpc.getCrawlMethod(),
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
    public suspend fun purge(request: PurgeRequest, headers: Metadata = Metadata()): PurgeResult =
        unaryRpc(
      channel,
      KnowledgeIngestServiceGrpc.getPurgeMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.knowledgebase.KnowledgeIngestService service based on
   * Kotlin coroutines.
   */
  public abstract class KnowledgeIngestServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeIngestService.Ingest.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun ingest(request: IngestRequest): IngestResult = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeIngestService.Ingest is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.knowledgebase.KnowledgeIngestService.IngestImmediate.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun ingestImmediate(request: IngestRequest): IngestResult = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeIngestService.IngestImmediate is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeIngestService.IngestQueue.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun ingestQueue(request: IngestRequest): IngestQueueAck = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeIngestService.IngestQueue is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeIngestService.IngestFile.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun ingestFile(request: IngestFileRequest): IngestResult = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeIngestService.IngestFile is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeIngestService.IngestFull.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun ingestFull(request: FullIngestRequest): FullIngestResult = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeIngestService.IngestFull is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.knowledgebase.KnowledgeIngestService.IngestFullAsync.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun ingestFullAsync(request: AsyncFullIngestRequest): AsyncIngestAck = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeIngestService.IngestFullAsync is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.knowledgebase.KnowledgeIngestService.IngestGitStructure.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun ingestGitStructure(request: GitStructureIngestRequest):
        GitStructureIngestResult = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeIngestService.IngestGitStructure is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.knowledgebase.KnowledgeIngestService.IngestGitCommits.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun ingestGitCommits(request: GitCommitIngestRequest): GitCommitIngestResult
        = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeIngestService.IngestGitCommits is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeIngestService.IngestCpg.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun ingestCpg(request: CpgIngestRequest): CpgIngestResult = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeIngestService.IngestCpg is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeIngestService.Crawl.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun crawl(request: CrawlRequest): IngestResult = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeIngestService.Crawl is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeIngestService.Purge.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun purge(request: PurgeRequest): PurgeResult = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeIngestService.Purge is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeIngestServiceGrpc.getIngestMethod(),
      implementation = ::ingest
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeIngestServiceGrpc.getIngestImmediateMethod(),
      implementation = ::ingestImmediate
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeIngestServiceGrpc.getIngestQueueMethod(),
      implementation = ::ingestQueue
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeIngestServiceGrpc.getIngestFileMethod(),
      implementation = ::ingestFile
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeIngestServiceGrpc.getIngestFullMethod(),
      implementation = ::ingestFull
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeIngestServiceGrpc.getIngestFullAsyncMethod(),
      implementation = ::ingestFullAsync
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeIngestServiceGrpc.getIngestGitStructureMethod(),
      implementation = ::ingestGitStructure
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeIngestServiceGrpc.getIngestGitCommitsMethod(),
      implementation = ::ingestGitCommits
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeIngestServiceGrpc.getIngestCpgMethod(),
      implementation = ::ingestCpg
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeIngestServiceGrpc.getCrawlMethod(),
      implementation = ::crawl
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeIngestServiceGrpc.getPurgeMethod(),
      implementation = ::purge
    )).build()
  }
}
