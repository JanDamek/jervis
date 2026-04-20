package com.jervis.contracts.server

import com.jervis.contracts.server.ServerProjectManagementServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerProjectManagementService.
 */
public object ServerProjectManagementServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerProjectManagementServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val listClientsMethod: MethodDescriptor<ListClientsRequest, ClientList>
    @JvmStatic
    get() = ServerProjectManagementServiceGrpc.getListClientsMethod()

  public val createClientMethod: MethodDescriptor<CreateClientRequest, CreateClientResponse>
    @JvmStatic
    get() = ServerProjectManagementServiceGrpc.getCreateClientMethod()

  public val listProjectsMethod: MethodDescriptor<ListProjectsRequest, ProjectList>
    @JvmStatic
    get() = ServerProjectManagementServiceGrpc.getListProjectsMethod()

  public val createProjectMethod: MethodDescriptor<CreateProjectRequest, CreateProjectResponse>
    @JvmStatic
    get() = ServerProjectManagementServiceGrpc.getCreateProjectMethod()

  public val updateProjectMethod: MethodDescriptor<UpdateProjectRequest, Project>
    @JvmStatic
    get() = ServerProjectManagementServiceGrpc.getUpdateProjectMethod()

  public val listConnectionsMethod: MethodDescriptor<ListConnectionsRequest, ConnectionSummaryList>
    @JvmStatic
    get() = ServerProjectManagementServiceGrpc.getListConnectionsMethod()

  public val createConnectionMethod:
      MethodDescriptor<CreateConnectionRequest, CreateConnectionResponse>
    @JvmStatic
    get() = ServerProjectManagementServiceGrpc.getCreateConnectionMethod()

  public val getStackRecommendationsMethod:
      MethodDescriptor<GetStackRecommendationsRequest, ProjectRecommendations>
    @JvmStatic
    get() = ServerProjectManagementServiceGrpc.getGetStackRecommendationsMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerProjectManagementService service as suspending coroutines.
   */
  @StubFor(ServerProjectManagementServiceGrpc::class)
  public class ServerProjectManagementServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerProjectManagementServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerProjectManagementServiceCoroutineStub = ServerProjectManagementServiceCoroutineStub(channel, callOptions)

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
    public suspend fun listClients(request: ListClientsRequest, headers: Metadata = Metadata()): ClientList = unaryRpc(
      channel,
      ServerProjectManagementServiceGrpc.getListClientsMethod(),
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
    public suspend fun createClient(request: CreateClientRequest, headers: Metadata = Metadata()): CreateClientResponse = unaryRpc(
      channel,
      ServerProjectManagementServiceGrpc.getCreateClientMethod(),
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
    public suspend fun listProjects(request: ListProjectsRequest, headers: Metadata = Metadata()): ProjectList = unaryRpc(
      channel,
      ServerProjectManagementServiceGrpc.getListProjectsMethod(),
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
    public suspend fun createProject(request: CreateProjectRequest, headers: Metadata = Metadata()): CreateProjectResponse = unaryRpc(
      channel,
      ServerProjectManagementServiceGrpc.getCreateProjectMethod(),
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
    public suspend fun updateProject(request: UpdateProjectRequest, headers: Metadata = Metadata()): Project = unaryRpc(
      channel,
      ServerProjectManagementServiceGrpc.getUpdateProjectMethod(),
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
    public suspend fun listConnections(request: ListConnectionsRequest, headers: Metadata = Metadata()): ConnectionSummaryList = unaryRpc(
      channel,
      ServerProjectManagementServiceGrpc.getListConnectionsMethod(),
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
    public suspend fun createConnection(request: CreateConnectionRequest, headers: Metadata = Metadata()): CreateConnectionResponse = unaryRpc(
      channel,
      ServerProjectManagementServiceGrpc.getCreateConnectionMethod(),
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
    public suspend fun getStackRecommendations(request: GetStackRecommendationsRequest, headers: Metadata = Metadata()): ProjectRecommendations = unaryRpc(
      channel,
      ServerProjectManagementServiceGrpc.getGetStackRecommendationsMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerProjectManagementService service based on Kotlin coroutines.
   */
  public abstract class ServerProjectManagementServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerProjectManagementService.ListClients.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listClients(request: ListClientsRequest): ClientList = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerProjectManagementService.ListClients is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerProjectManagementService.CreateClient.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun createClient(request: CreateClientRequest): CreateClientResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerProjectManagementService.CreateClient is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerProjectManagementService.ListProjects.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listProjects(request: ListProjectsRequest): ProjectList = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerProjectManagementService.ListProjects is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerProjectManagementService.CreateProject.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun createProject(request: CreateProjectRequest): CreateProjectResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerProjectManagementService.CreateProject is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerProjectManagementService.UpdateProject.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun updateProject(request: UpdateProjectRequest): Project = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerProjectManagementService.UpdateProject is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerProjectManagementService.ListConnections.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listConnections(request: ListConnectionsRequest): ConnectionSummaryList = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerProjectManagementService.ListConnections is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerProjectManagementService.CreateConnection.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun createConnection(request: CreateConnectionRequest): CreateConnectionResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerProjectManagementService.CreateConnection is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerProjectManagementService.GetStackRecommendations.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getStackRecommendations(request: GetStackRecommendationsRequest): ProjectRecommendations = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerProjectManagementService.GetStackRecommendations is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerProjectManagementServiceGrpc.getListClientsMethod(),
      implementation = ::listClients
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerProjectManagementServiceGrpc.getCreateClientMethod(),
      implementation = ::createClient
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerProjectManagementServiceGrpc.getListProjectsMethod(),
      implementation = ::listProjects
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerProjectManagementServiceGrpc.getCreateProjectMethod(),
      implementation = ::createProject
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerProjectManagementServiceGrpc.getUpdateProjectMethod(),
      implementation = ::updateProject
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerProjectManagementServiceGrpc.getListConnectionsMethod(),
      implementation = ::listConnections
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerProjectManagementServiceGrpc.getCreateConnectionMethod(),
      implementation = ::createConnection
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerProjectManagementServiceGrpc.getGetStackRecommendationsMethod(),
      implementation = ::getStackRecommendations
    )).build()
  }
}
