package com.jervis.contracts.server

import com.jervis.contracts.server.ServerEnvironmentK8sServiceGrpc.getServiceDescriptor
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
 * jervis.server.ServerEnvironmentK8sService.
 */
public object ServerEnvironmentK8sServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerEnvironmentK8sServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val listNamespaceResourcesMethod:
      MethodDescriptor<ListNamespaceResourcesRequest, ListNamespaceResourcesResponse>
    @JvmStatic
    get() = ServerEnvironmentK8sServiceGrpc.getListNamespaceResourcesMethod()

  public val getPodLogsMethod: MethodDescriptor<GetPodLogsRequest, GetPodLogsResponse>
    @JvmStatic
    get() = ServerEnvironmentK8sServiceGrpc.getGetPodLogsMethod()

  public val getDeploymentStatusMethod:
      MethodDescriptor<GetDeploymentStatusRequest, GetDeploymentStatusResponse>
    @JvmStatic
    get() = ServerEnvironmentK8sServiceGrpc.getGetDeploymentStatusMethod()

  public val scaleDeploymentMethod:
      MethodDescriptor<ScaleDeploymentRequest, ScaleDeploymentResponse>
    @JvmStatic
    get() = ServerEnvironmentK8sServiceGrpc.getScaleDeploymentMethod()

  public val restartDeploymentMethod:
      MethodDescriptor<RestartDeploymentRequest, RestartDeploymentResponse>
    @JvmStatic
    get() = ServerEnvironmentK8sServiceGrpc.getRestartDeploymentMethod()

  public val getNamespaceStatusMethod:
      MethodDescriptor<GetNamespaceStatusRequest, GetNamespaceStatusResponse>
    @JvmStatic
    get() = ServerEnvironmentK8sServiceGrpc.getGetNamespaceStatusMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerEnvironmentK8sService service as suspending
   * coroutines.
   */
  @StubFor(ServerEnvironmentK8sServiceGrpc::class)
  public class ServerEnvironmentK8sServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerEnvironmentK8sServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions):
        ServerEnvironmentK8sServiceCoroutineStub = ServerEnvironmentK8sServiceCoroutineStub(channel,
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
    public suspend fun listNamespaceResources(request: ListNamespaceResourcesRequest,
        headers: Metadata = Metadata()): ListNamespaceResourcesResponse = unaryRpc(
      channel,
      ServerEnvironmentK8sServiceGrpc.getListNamespaceResourcesMethod(),
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
    public suspend fun getPodLogs(request: GetPodLogsRequest, headers: Metadata = Metadata()):
        GetPodLogsResponse = unaryRpc(
      channel,
      ServerEnvironmentK8sServiceGrpc.getGetPodLogsMethod(),
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
    public suspend fun getDeploymentStatus(request: GetDeploymentStatusRequest, headers: Metadata =
        Metadata()): GetDeploymentStatusResponse = unaryRpc(
      channel,
      ServerEnvironmentK8sServiceGrpc.getGetDeploymentStatusMethod(),
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
    public suspend fun scaleDeployment(request: ScaleDeploymentRequest, headers: Metadata =
        Metadata()): ScaleDeploymentResponse = unaryRpc(
      channel,
      ServerEnvironmentK8sServiceGrpc.getScaleDeploymentMethod(),
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
    public suspend fun restartDeployment(request: RestartDeploymentRequest, headers: Metadata =
        Metadata()): RestartDeploymentResponse = unaryRpc(
      channel,
      ServerEnvironmentK8sServiceGrpc.getRestartDeploymentMethod(),
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
    public suspend fun getNamespaceStatus(request: GetNamespaceStatusRequest, headers: Metadata =
        Metadata()): GetNamespaceStatusResponse = unaryRpc(
      channel,
      ServerEnvironmentK8sServiceGrpc.getGetNamespaceStatusMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerEnvironmentK8sService service based on
   * Kotlin coroutines.
   */
  public abstract class ServerEnvironmentK8sServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for
     * jervis.server.ServerEnvironmentK8sService.ListNamespaceResources.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listNamespaceResources(request: ListNamespaceResourcesRequest):
        ListNamespaceResourcesResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentK8sService.ListNamespaceResources is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerEnvironmentK8sService.GetPodLogs.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getPodLogs(request: GetPodLogsRequest): GetPodLogsResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentK8sService.GetPodLogs is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.server.ServerEnvironmentK8sService.GetDeploymentStatus.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getDeploymentStatus(request: GetDeploymentStatusRequest):
        GetDeploymentStatusResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentK8sService.GetDeploymentStatus is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerEnvironmentK8sService.ScaleDeployment.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun scaleDeployment(request: ScaleDeploymentRequest):
        ScaleDeploymentResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentK8sService.ScaleDeployment is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.server.ServerEnvironmentK8sService.RestartDeployment.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun restartDeployment(request: RestartDeploymentRequest):
        RestartDeploymentResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentK8sService.RestartDeployment is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.server.ServerEnvironmentK8sService.GetNamespaceStatus.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getNamespaceStatus(request: GetNamespaceStatusRequest):
        GetNamespaceStatusResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerEnvironmentK8sService.GetNamespaceStatus is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentK8sServiceGrpc.getListNamespaceResourcesMethod(),
      implementation = ::listNamespaceResources
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentK8sServiceGrpc.getGetPodLogsMethod(),
      implementation = ::getPodLogs
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentK8sServiceGrpc.getGetDeploymentStatusMethod(),
      implementation = ::getDeploymentStatus
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentK8sServiceGrpc.getScaleDeploymentMethod(),
      implementation = ::scaleDeployment
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentK8sServiceGrpc.getRestartDeploymentMethod(),
      implementation = ::restartDeployment
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerEnvironmentK8sServiceGrpc.getGetNamespaceStatusMethod(),
      implementation = ::getNamespaceStatus
    )).build()
  }
}
