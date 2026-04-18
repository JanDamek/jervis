package com.jervis.contracts.server

import com.jervis.contracts.server.ServerEnvironmentServiceGrpc.getServiceDescriptor
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
 * jervis.server.ServerEnvironmentService.
 */
public object ServerEnvironmentServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerEnvironmentServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val listEnvironmentsMethod: MethodDescriptor<ListEnvironmentsRequest, EnvironmentList>
    @JvmStatic
    get() = ServerEnvironmentServiceGrpc.getListEnvironmentsMethod()

  public val getEnvironmentMethod: MethodDescriptor<GetEnvironmentRequest, Environment>
    @JvmStatic
    get() = ServerEnvironmentServiceGrpc.getGetEnvironmentMethod()

  public val createEnvironmentMethod: MethodDescriptor<CreateEnvironmentRequest, Environment>
    @JvmStatic
    get() = ServerEnvironmentServiceGrpc.getCreateEnvironmentMethod()

  public val deleteEnvironmentMethod:
      MethodDescriptor<DeleteEnvironmentRequest, DeleteEnvironmentResponse>
    @JvmStatic
    get() = ServerEnvironmentServiceGrpc.getDeleteEnvironmentMethod()

  public val addComponentMethod: MethodDescriptor<AddComponentRequest, Environment>
    @JvmStatic
    get() = ServerEnvironmentServiceGrpc.getAddComponentMethod()

  public val configureComponentMethod: MethodDescriptor<ConfigureComponentRequest, Environment>
    @JvmStatic
    get() = ServerEnvironmentServiceGrpc.getConfigureComponentMethod()

  public val deployEnvironmentMethod: MethodDescriptor<EnvironmentIdRequest, Environment>
    @JvmStatic
    get() = ServerEnvironmentServiceGrpc.getDeployEnvironmentMethod()

  public val stopEnvironmentMethod: MethodDescriptor<EnvironmentIdRequest, Environment>
    @JvmStatic
    get() = ServerEnvironmentServiceGrpc.getStopEnvironmentMethod()

  public val syncEnvironmentMethod: MethodDescriptor<EnvironmentIdRequest, Environment>
    @JvmStatic
    get() = ServerEnvironmentServiceGrpc.getSyncEnvironmentMethod()

  public val getEnvironmentStatusMethod: MethodDescriptor<EnvironmentIdRequest, EnvironmentStatus>
    @JvmStatic
    get() = ServerEnvironmentServiceGrpc.getGetEnvironmentStatusMethod()

  public val cloneEnvironmentMethod: MethodDescriptor<CloneEnvironmentRequest, Environment>
    @JvmStatic
    get() = ServerEnvironmentServiceGrpc.getCloneEnvironmentMethod()

  public val addPropertyMappingMethod: MethodDescriptor<AddPropertyMappingRequest, Environment>
    @JvmStatic
    get() = ServerEnvironmentServiceGrpc.getAddPropertyMappingMethod()

  public val autoSuggestPropertyMappingsMethod:
      MethodDescriptor<EnvironmentIdRequest, AutoSuggestPropertyMappingsResponse>
    @JvmStatic
    get() = ServerEnvironmentServiceGrpc.getAutoSuggestPropertyMappingsMethod()

  public val discoverNamespaceMethod: MethodDescriptor<DiscoverNamespaceRequest, Environment>
    @JvmStatic
    get() = ServerEnvironmentServiceGrpc.getDiscoverNamespaceMethod()

  public val replicateEnvironmentMethod: MethodDescriptor<ReplicateEnvironmentRequest, Environment>
    @JvmStatic
    get() = ServerEnvironmentServiceGrpc.getReplicateEnvironmentMethod()

  public val syncFromK8sMethod: MethodDescriptor<EnvironmentIdRequest, Environment>
    @JvmStatic
    get() = ServerEnvironmentServiceGrpc.getSyncFromK8sMethod()

  public val listComponentTemplatesMethod:
      MethodDescriptor<ListComponentTemplatesRequest, ComponentTemplateList>
    @JvmStatic
    get() = ServerEnvironmentServiceGrpc.getListComponentTemplatesMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerEnvironmentService service as suspending
   * coroutines.
   */
  @StubFor(ServerEnvironmentServiceGrpc::class)
  public class ServerEnvironmentServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerEnvironmentServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions):
        ServerEnvironmentServiceCoroutineStub = ServerEnvironmentServiceCoroutineStub(channel,
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
    public suspend fun listEnvironments(request: ListEnvironmentsRequest, headers: Metadata =
        Metadata()): EnvironmentList = unaryRpc(
      channel,
      ServerEnvironmentServiceGrpc.getListEnvironmentsMethod(),
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
    public suspend fun getEnvironment(request: GetEnvironmentRequest, headers: Metadata =
        Metadata()): Environment = unaryRpc(
      channel,
      ServerEnvironmentServiceGrpc.getGetEnvironmentMethod(),
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
    public suspend fun createEnvironment(request: CreateEnvironmentRequest, headers: Metadata =
        Metadata()): Environment = unaryRpc(
      channel,
      ServerEnvironmentServiceGrpc.getCreateEnvironmentMethod(),
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
    public suspend fun deleteEnvironment(request: DeleteEnvironmentRequest, headers: Metadata =
        Metadata()): DeleteEnvironmentResponse = unaryRpc(
      channel,
      ServerEnvironmentServiceGrpc.getDeleteEnvironmentMethod(),
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
    public suspend fun addComponent(request: AddComponentRequest, headers: Metadata = Metadata()):
        Environment = unaryRpc(
      channel,
      ServerEnvironmentServiceGrpc.getAddComponentMethod(),
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
    public suspend fun configureComponent(request: ConfigureComponentRequest, headers: Metadata =
        Metadata()): Environment = unaryRpc(
      channel,
      ServerEnvironmentServiceGrpc.getConfigureComponentMethod(),
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
    public suspend fun deployEnvironment(request: EnvironmentIdRequest, headers: Metadata =
        Metadata()): Environment = unaryRpc(
      channel,
      ServerEnvironmentServiceGrpc.getDeployEnvironmentMethod(),
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
    public suspend fun stopEnvironment(request: EnvironmentIdRequest, headers: Metadata =
        Metadata()): Environment = unaryRpc(
      channel,
      ServerEnvironmentServiceGrpc.getStopEnvironmentMethod(),
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
    public suspend fun syncEnvironment(request: EnvironmentIdRequest, headers: Metadata =
        Metadata()): Environment = unaryRpc(
      channel,
      ServerEnvironmentServiceGrpc.getSyncEnvironmentMethod(),
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
    public suspend fun getEnvironmentStatus(request: EnvironmentIdRequest, headers: Metadata =
        Metadata()): EnvironmentStatus = unaryRpc(
      channel,
      ServerEnvironmentServiceGrpc.getGetEnvironmentStatusMethod(),
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
    public suspend fun cloneEnvironment(request: CloneEnvironmentRequest, headers: Metadata =
        Metadata()): Environment = unaryRpc(
      channel,
      ServerEnvironmentServiceGrpc.getCloneEnvironmentMethod(),
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
    public suspend fun addPropertyMapping(request: AddPropertyMappingRequest, headers: Metadata =
        Metadata()): Environment = unaryRpc(
      channel,
      ServerEnvironmentServiceGrpc.getAddPropertyMappingMethod(),
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
    public suspend fun autoSuggestPropertyMappings(request: EnvironmentIdRequest, headers: Metadata
        = Metadata()): AutoSuggestPropertyMappingsResponse = unaryRpc(
      channel,
      ServerEnvironmentServiceGrpc.getAutoSuggestPropertyMappingsMethod(),
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
    public suspend fun discoverNamespace(request: DiscoverNamespaceRequest, headers: Metadata =
        Metadata()): Environment = unaryRpc(
      channel,
      ServerEnvironmentServiceGrpc.getDiscoverNamespaceMethod(),
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
    public suspend fun replicateEnvironment(request: ReplicateEnvironmentRequest, headers: Metadata
        = Metadata()): Environment = unaryRpc(
      channel,
      ServerEnvironmentServiceGrpc.getReplicateEnvironmentMethod(),
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
    public suspend fun syncFromK8s(request: EnvironmentIdRequest, headers: Metadata = Metadata()):
        Environment = unaryRpc(
      channel,
      ServerEnvironmentServiceGrpc.getSyncFromK8sMethod(),
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
    public suspend fun listComponentTemplates(request: ListComponentTemplatesRequest,
        headers: Metadata = Metadata()): ComponentTemplateList = unaryRpc(
      channel,
      ServerEnvironmentServiceGrpc.getListComponentTemplatesMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerEnvironmentService service based on Kotlin
   * coroutines.
   */
  public abstract class ServerEnvironmentServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerEnvironmentService.ListEnvironments.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listEnvironments(request: ListEnvironmentsRequest): EnvironmentList =
        throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentService.ListEnvironments is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerEnvironmentService.GetEnvironment.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getEnvironment(request: GetEnvironmentRequest): Environment = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentService.GetEnvironment is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerEnvironmentService.CreateEnvironment.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun createEnvironment(request: CreateEnvironmentRequest): Environment =
        throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentService.CreateEnvironment is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerEnvironmentService.DeleteEnvironment.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun deleteEnvironment(request: DeleteEnvironmentRequest):
        DeleteEnvironmentResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentService.DeleteEnvironment is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerEnvironmentService.AddComponent.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun addComponent(request: AddComponentRequest): Environment = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentService.AddComponent is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerEnvironmentService.ConfigureComponent.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun configureComponent(request: ConfigureComponentRequest): Environment =
        throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentService.ConfigureComponent is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerEnvironmentService.DeployEnvironment.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun deployEnvironment(request: EnvironmentIdRequest): Environment = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentService.DeployEnvironment is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerEnvironmentService.StopEnvironment.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun stopEnvironment(request: EnvironmentIdRequest): Environment = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentService.StopEnvironment is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerEnvironmentService.SyncEnvironment.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun syncEnvironment(request: EnvironmentIdRequest): Environment = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentService.SyncEnvironment is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.server.ServerEnvironmentService.GetEnvironmentStatus.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getEnvironmentStatus(request: EnvironmentIdRequest): EnvironmentStatus =
        throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentService.GetEnvironmentStatus is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerEnvironmentService.CloneEnvironment.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun cloneEnvironment(request: CloneEnvironmentRequest): Environment = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentService.CloneEnvironment is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerEnvironmentService.AddPropertyMapping.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun addPropertyMapping(request: AddPropertyMappingRequest): Environment =
        throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentService.AddPropertyMapping is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.server.ServerEnvironmentService.AutoSuggestPropertyMappings.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun autoSuggestPropertyMappings(request: EnvironmentIdRequest):
        AutoSuggestPropertyMappingsResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentService.AutoSuggestPropertyMappings is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerEnvironmentService.DiscoverNamespace.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun discoverNamespace(request: DiscoverNamespaceRequest): Environment =
        throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentService.DiscoverNamespace is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.server.ServerEnvironmentService.ReplicateEnvironment.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun replicateEnvironment(request: ReplicateEnvironmentRequest): Environment
        = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentService.ReplicateEnvironment is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerEnvironmentService.SyncFromK8s.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun syncFromK8s(request: EnvironmentIdRequest): Environment = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentService.SyncFromK8s is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.server.ServerEnvironmentService.ListComponentTemplates.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listComponentTemplates(request: ListComponentTemplatesRequest):
        ComponentTemplateList = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentService.ListComponentTemplates is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentServiceGrpc.getListEnvironmentsMethod(),
      implementation = ::listEnvironments
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentServiceGrpc.getGetEnvironmentMethod(),
      implementation = ::getEnvironment
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentServiceGrpc.getCreateEnvironmentMethod(),
      implementation = ::createEnvironment
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentServiceGrpc.getDeleteEnvironmentMethod(),
      implementation = ::deleteEnvironment
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentServiceGrpc.getAddComponentMethod(),
      implementation = ::addComponent
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentServiceGrpc.getConfigureComponentMethod(),
      implementation = ::configureComponent
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentServiceGrpc.getDeployEnvironmentMethod(),
      implementation = ::deployEnvironment
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentServiceGrpc.getStopEnvironmentMethod(),
      implementation = ::stopEnvironment
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentServiceGrpc.getSyncEnvironmentMethod(),
      implementation = ::syncEnvironment
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentServiceGrpc.getGetEnvironmentStatusMethod(),
      implementation = ::getEnvironmentStatus
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentServiceGrpc.getCloneEnvironmentMethod(),
      implementation = ::cloneEnvironment
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentServiceGrpc.getAddPropertyMappingMethod(),
      implementation = ::addPropertyMapping
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentServiceGrpc.getAutoSuggestPropertyMappingsMethod(),
      implementation = ::autoSuggestPropertyMappings
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentServiceGrpc.getDiscoverNamespaceMethod(),
      implementation = ::discoverNamespace
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentServiceGrpc.getReplicateEnvironmentMethod(),
      implementation = ::replicateEnvironment
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentServiceGrpc.getSyncFromK8sMethod(),
      implementation = ::syncFromK8s
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentServiceGrpc.getListComponentTemplatesMethod(),
      implementation = ::listComponentTemplates
    )).build()
  }
}
