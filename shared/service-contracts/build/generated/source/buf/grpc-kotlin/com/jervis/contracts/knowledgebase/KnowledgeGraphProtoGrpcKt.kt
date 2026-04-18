package com.jervis.contracts.knowledgebase

import com.jervis.contracts.knowledgebase.KnowledgeGraphServiceGrpc.getServiceDescriptor
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
 * jervis.knowledgebase.KnowledgeGraphService.
 */
public object KnowledgeGraphServiceGrpcKt {
  public const val SERVICE_NAME: String = KnowledgeGraphServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val traverseMethod: MethodDescriptor<TraversalRequest, GraphNodeList>
    @JvmStatic
    get() = KnowledgeGraphServiceGrpc.getTraverseMethod()

  public val getNodeMethod: MethodDescriptor<GetNodeRequest, GraphNode>
    @JvmStatic
    get() = KnowledgeGraphServiceGrpc.getGetNodeMethod()

  public val searchNodesMethod: MethodDescriptor<SearchNodesRequest, GraphNodeList>
    @JvmStatic
    get() = KnowledgeGraphServiceGrpc.getSearchNodesMethod()

  public val getNodeEvidenceMethod: MethodDescriptor<GetNodeRequest, EvidencePack>
    @JvmStatic
    get() = KnowledgeGraphServiceGrpc.getGetNodeEvidenceMethod()

  public val listQueryEntitiesMethod: MethodDescriptor<ListQueryEntitiesRequest, EntityList>
    @JvmStatic
    get() = KnowledgeGraphServiceGrpc.getListQueryEntitiesMethod()

  public val resolveAliasMethod: MethodDescriptor<ResolveAliasRequest, AliasResolveResult>
    @JvmStatic
    get() = KnowledgeGraphServiceGrpc.getResolveAliasMethod()

  public val listAliasesMethod: MethodDescriptor<ListAliasesRequest, AliasList>
    @JvmStatic
    get() = KnowledgeGraphServiceGrpc.getListAliasesMethod()

  public val getAliasStatsMethod: MethodDescriptor<AliasStatsRequest, AliasStats>
    @JvmStatic
    get() = KnowledgeGraphServiceGrpc.getGetAliasStatsMethod()

  public val registerAliasMethod: MethodDescriptor<RegisterAliasRequest, AliasAck>
    @JvmStatic
    get() = KnowledgeGraphServiceGrpc.getRegisterAliasMethod()

  public val mergeAliasMethod: MethodDescriptor<MergeAliasRequest, AliasAck>
    @JvmStatic
    get() = KnowledgeGraphServiceGrpc.getMergeAliasMethod()

  public val thoughtTraverseMethod:
      MethodDescriptor<ThoughtTraversalRequest, ThoughtTraversalResult>
    @JvmStatic
    get() = KnowledgeGraphServiceGrpc.getThoughtTraverseMethod()

  public val thoughtReinforceMethod: MethodDescriptor<ThoughtReinforceRequest, ThoughtAck>
    @JvmStatic
    get() = KnowledgeGraphServiceGrpc.getThoughtReinforceMethod()

  public val thoughtCreateMethod: MethodDescriptor<ThoughtCreateRequest, ThoughtAck>
    @JvmStatic
    get() = KnowledgeGraphServiceGrpc.getThoughtCreateMethod()

  public val thoughtBootstrapMethod: MethodDescriptor<ThoughtBootstrapRequest, ThoughtAck>
    @JvmStatic
    get() = KnowledgeGraphServiceGrpc.getThoughtBootstrapMethod()

  public val thoughtMaintainMethod: MethodDescriptor<ThoughtMaintenanceRequest, ThoughtAck>
    @JvmStatic
    get() = KnowledgeGraphServiceGrpc.getThoughtMaintainMethod()

  public val thoughtStatsMethod: MethodDescriptor<ThoughtStatsRequest, ThoughtStatsResult>
    @JvmStatic
    get() = KnowledgeGraphServiceGrpc.getThoughtStatsMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.knowledgebase.KnowledgeGraphService service as
   * suspending coroutines.
   */
  @StubFor(KnowledgeGraphServiceGrpc::class)
  public class KnowledgeGraphServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<KnowledgeGraphServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions):
        KnowledgeGraphServiceCoroutineStub = KnowledgeGraphServiceCoroutineStub(channel,
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
    public suspend fun traverse(request: TraversalRequest, headers: Metadata = Metadata()):
        GraphNodeList = unaryRpc(
      channel,
      KnowledgeGraphServiceGrpc.getTraverseMethod(),
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
    public suspend fun getNode(request: GetNodeRequest, headers: Metadata = Metadata()): GraphNode =
        unaryRpc(
      channel,
      KnowledgeGraphServiceGrpc.getGetNodeMethod(),
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
    public suspend fun searchNodes(request: SearchNodesRequest, headers: Metadata = Metadata()):
        GraphNodeList = unaryRpc(
      channel,
      KnowledgeGraphServiceGrpc.getSearchNodesMethod(),
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
    public suspend fun getNodeEvidence(request: GetNodeRequest, headers: Metadata = Metadata()):
        EvidencePack = unaryRpc(
      channel,
      KnowledgeGraphServiceGrpc.getGetNodeEvidenceMethod(),
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
    public suspend fun listQueryEntities(request: ListQueryEntitiesRequest, headers: Metadata =
        Metadata()): EntityList = unaryRpc(
      channel,
      KnowledgeGraphServiceGrpc.getListQueryEntitiesMethod(),
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
    public suspend fun resolveAlias(request: ResolveAliasRequest, headers: Metadata = Metadata()):
        AliasResolveResult = unaryRpc(
      channel,
      KnowledgeGraphServiceGrpc.getResolveAliasMethod(),
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
    public suspend fun listAliases(request: ListAliasesRequest, headers: Metadata = Metadata()):
        AliasList = unaryRpc(
      channel,
      KnowledgeGraphServiceGrpc.getListAliasesMethod(),
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
    public suspend fun getAliasStats(request: AliasStatsRequest, headers: Metadata = Metadata()):
        AliasStats = unaryRpc(
      channel,
      KnowledgeGraphServiceGrpc.getGetAliasStatsMethod(),
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
    public suspend fun registerAlias(request: RegisterAliasRequest, headers: Metadata = Metadata()):
        AliasAck = unaryRpc(
      channel,
      KnowledgeGraphServiceGrpc.getRegisterAliasMethod(),
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
    public suspend fun mergeAlias(request: MergeAliasRequest, headers: Metadata = Metadata()):
        AliasAck = unaryRpc(
      channel,
      KnowledgeGraphServiceGrpc.getMergeAliasMethod(),
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
    public suspend fun thoughtTraverse(request: ThoughtTraversalRequest, headers: Metadata =
        Metadata()): ThoughtTraversalResult = unaryRpc(
      channel,
      KnowledgeGraphServiceGrpc.getThoughtTraverseMethod(),
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
    public suspend fun thoughtReinforce(request: ThoughtReinforceRequest, headers: Metadata =
        Metadata()): ThoughtAck = unaryRpc(
      channel,
      KnowledgeGraphServiceGrpc.getThoughtReinforceMethod(),
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
    public suspend fun thoughtCreate(request: ThoughtCreateRequest, headers: Metadata = Metadata()):
        ThoughtAck = unaryRpc(
      channel,
      KnowledgeGraphServiceGrpc.getThoughtCreateMethod(),
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
    public suspend fun thoughtBootstrap(request: ThoughtBootstrapRequest, headers: Metadata =
        Metadata()): ThoughtAck = unaryRpc(
      channel,
      KnowledgeGraphServiceGrpc.getThoughtBootstrapMethod(),
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
    public suspend fun thoughtMaintain(request: ThoughtMaintenanceRequest, headers: Metadata =
        Metadata()): ThoughtAck = unaryRpc(
      channel,
      KnowledgeGraphServiceGrpc.getThoughtMaintainMethod(),
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
    public suspend fun thoughtStats(request: ThoughtStatsRequest, headers: Metadata = Metadata()):
        ThoughtStatsResult = unaryRpc(
      channel,
      KnowledgeGraphServiceGrpc.getThoughtStatsMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.knowledgebase.KnowledgeGraphService service based on
   * Kotlin coroutines.
   */
  public abstract class KnowledgeGraphServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeGraphService.Traverse.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun traverse(request: TraversalRequest): GraphNodeList = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeGraphService.Traverse is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeGraphService.GetNode.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getNode(request: GetNodeRequest): GraphNode = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeGraphService.GetNode is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeGraphService.SearchNodes.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun searchNodes(request: SearchNodesRequest): GraphNodeList = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeGraphService.SearchNodes is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.knowledgebase.KnowledgeGraphService.GetNodeEvidence.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getNodeEvidence(request: GetNodeRequest): EvidencePack = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeGraphService.GetNodeEvidence is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.knowledgebase.KnowledgeGraphService.ListQueryEntities.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listQueryEntities(request: ListQueryEntitiesRequest): EntityList = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeGraphService.ListQueryEntities is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeGraphService.ResolveAlias.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun resolveAlias(request: ResolveAliasRequest): AliasResolveResult = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeGraphService.ResolveAlias is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeGraphService.ListAliases.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listAliases(request: ListAliasesRequest): AliasList = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeGraphService.ListAliases is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeGraphService.GetAliasStats.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getAliasStats(request: AliasStatsRequest): AliasStats = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeGraphService.GetAliasStats is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeGraphService.RegisterAlias.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun registerAlias(request: RegisterAliasRequest): AliasAck = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeGraphService.RegisterAlias is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeGraphService.MergeAlias.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun mergeAlias(request: MergeAliasRequest): AliasAck = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeGraphService.MergeAlias is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.knowledgebase.KnowledgeGraphService.ThoughtTraverse.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun thoughtTraverse(request: ThoughtTraversalRequest):
        ThoughtTraversalResult = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeGraphService.ThoughtTraverse is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.knowledgebase.KnowledgeGraphService.ThoughtReinforce.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun thoughtReinforce(request: ThoughtReinforceRequest): ThoughtAck = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeGraphService.ThoughtReinforce is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeGraphService.ThoughtCreate.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun thoughtCreate(request: ThoughtCreateRequest): ThoughtAck = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeGraphService.ThoughtCreate is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.knowledgebase.KnowledgeGraphService.ThoughtBootstrap.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun thoughtBootstrap(request: ThoughtBootstrapRequest): ThoughtAck = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeGraphService.ThoughtBootstrap is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.knowledgebase.KnowledgeGraphService.ThoughtMaintain.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun thoughtMaintain(request: ThoughtMaintenanceRequest): ThoughtAck = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeGraphService.ThoughtMaintain is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.knowledgebase.KnowledgeGraphService.ThoughtStats.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun thoughtStats(request: ThoughtStatsRequest): ThoughtStatsResult = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.knowledgebase.KnowledgeGraphService.ThoughtStats is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeGraphServiceGrpc.getTraverseMethod(),
      implementation = ::traverse
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeGraphServiceGrpc.getGetNodeMethod(),
      implementation = ::getNode
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeGraphServiceGrpc.getSearchNodesMethod(),
      implementation = ::searchNodes
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeGraphServiceGrpc.getGetNodeEvidenceMethod(),
      implementation = ::getNodeEvidence
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeGraphServiceGrpc.getListQueryEntitiesMethod(),
      implementation = ::listQueryEntities
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeGraphServiceGrpc.getResolveAliasMethod(),
      implementation = ::resolveAlias
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeGraphServiceGrpc.getListAliasesMethod(),
      implementation = ::listAliases
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeGraphServiceGrpc.getGetAliasStatsMethod(),
      implementation = ::getAliasStats
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeGraphServiceGrpc.getRegisterAliasMethod(),
      implementation = ::registerAlias
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeGraphServiceGrpc.getMergeAliasMethod(),
      implementation = ::mergeAlias
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeGraphServiceGrpc.getThoughtTraverseMethod(),
      implementation = ::thoughtTraverse
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeGraphServiceGrpc.getThoughtReinforceMethod(),
      implementation = ::thoughtReinforce
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeGraphServiceGrpc.getThoughtCreateMethod(),
      implementation = ::thoughtCreate
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeGraphServiceGrpc.getThoughtBootstrapMethod(),
      implementation = ::thoughtBootstrap
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeGraphServiceGrpc.getThoughtMaintainMethod(),
      implementation = ::thoughtMaintain
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = KnowledgeGraphServiceGrpc.getThoughtStatsMethod(),
      implementation = ::thoughtStats
    )).build()
  }
}
