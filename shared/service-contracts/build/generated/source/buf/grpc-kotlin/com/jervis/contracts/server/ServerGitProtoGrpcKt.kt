package com.jervis.contracts.server

import com.jervis.contracts.server.ServerGitServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerGitService.
 */
public object ServerGitServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerGitServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val createRepositoryMethod:
      MethodDescriptor<CreateRepositoryRequest, CreateRepositoryResponse>
    @JvmStatic
    get() = ServerGitServiceGrpc.getCreateRepositoryMethod()

  public val initWorkspaceMethod: MethodDescriptor<InitWorkspaceRequest, InitWorkspaceResponse>
    @JvmStatic
    get() = ServerGitServiceGrpc.getInitWorkspaceMethod()

  public val getWorkspaceStatusMethod:
      MethodDescriptor<WorkspaceStatusRequest, WorkspaceStatusResponse>
    @JvmStatic
    get() = ServerGitServiceGrpc.getGetWorkspaceStatusMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerGitService service as suspending coroutines.
   */
  @StubFor(ServerGitServiceGrpc::class)
  public class ServerGitServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerGitServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerGitServiceCoroutineStub = ServerGitServiceCoroutineStub(channel, callOptions)

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
    public suspend fun createRepository(request: CreateRepositoryRequest, headers: Metadata = Metadata()): CreateRepositoryResponse = unaryRpc(
      channel,
      ServerGitServiceGrpc.getCreateRepositoryMethod(),
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
    public suspend fun initWorkspace(request: InitWorkspaceRequest, headers: Metadata = Metadata()): InitWorkspaceResponse = unaryRpc(
      channel,
      ServerGitServiceGrpc.getInitWorkspaceMethod(),
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
    public suspend fun getWorkspaceStatus(request: WorkspaceStatusRequest, headers: Metadata = Metadata()): WorkspaceStatusResponse = unaryRpc(
      channel,
      ServerGitServiceGrpc.getGetWorkspaceStatusMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerGitService service based on Kotlin coroutines.
   */
  public abstract class ServerGitServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerGitService.CreateRepository.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun createRepository(request: CreateRepositoryRequest): CreateRepositoryResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerGitService.CreateRepository is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerGitService.InitWorkspace.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun initWorkspace(request: InitWorkspaceRequest): InitWorkspaceResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerGitService.InitWorkspace is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerGitService.GetWorkspaceStatus.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getWorkspaceStatus(request: WorkspaceStatusRequest): WorkspaceStatusResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerGitService.GetWorkspaceStatus is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerGitServiceGrpc.getCreateRepositoryMethod(),
      implementation = ::createRepository
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerGitServiceGrpc.getInitWorkspaceMethod(),
      implementation = ::initWorkspace
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerGitServiceGrpc.getGetWorkspaceStatusMethod(),
      implementation = ::getWorkspaceStatus
    )).build()
  }
}
