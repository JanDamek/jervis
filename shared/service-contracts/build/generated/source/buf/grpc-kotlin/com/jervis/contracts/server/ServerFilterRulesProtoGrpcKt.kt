package com.jervis.contracts.server

import com.jervis.contracts.server.ServerFilterRulesServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerFilterRulesService.
 */
public object ServerFilterRulesServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerFilterRulesServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val createMethod: MethodDescriptor<CreateFilterRuleRequest, FilterRulePayload>
    @JvmStatic
    get() = ServerFilterRulesServiceGrpc.getCreateMethod()

  public val listMethod: MethodDescriptor<ListFilterRulesRequest, FilterRuleListPayload>
    @JvmStatic
    get() = ServerFilterRulesServiceGrpc.getListMethod()

  public val removeMethod: MethodDescriptor<RemoveFilterRuleRequest, RemoveFilterRuleResponse>
    @JvmStatic
    get() = ServerFilterRulesServiceGrpc.getRemoveMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerFilterRulesService service as suspending coroutines.
   */
  @StubFor(ServerFilterRulesServiceGrpc::class)
  public class ServerFilterRulesServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerFilterRulesServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerFilterRulesServiceCoroutineStub = ServerFilterRulesServiceCoroutineStub(channel, callOptions)

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
    public suspend fun create(request: CreateFilterRuleRequest, headers: Metadata = Metadata()): FilterRulePayload = unaryRpc(
      channel,
      ServerFilterRulesServiceGrpc.getCreateMethod(),
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
    public suspend fun list(request: ListFilterRulesRequest, headers: Metadata = Metadata()): FilterRuleListPayload = unaryRpc(
      channel,
      ServerFilterRulesServiceGrpc.getListMethod(),
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
    public suspend fun remove(request: RemoveFilterRuleRequest, headers: Metadata = Metadata()): RemoveFilterRuleResponse = unaryRpc(
      channel,
      ServerFilterRulesServiceGrpc.getRemoveMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerFilterRulesService service based on Kotlin coroutines.
   */
  public abstract class ServerFilterRulesServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerFilterRulesService.Create.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun create(request: CreateFilterRuleRequest): FilterRulePayload = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerFilterRulesService.Create is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerFilterRulesService.List.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun list(request: ListFilterRulesRequest): FilterRuleListPayload = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerFilterRulesService.List is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerFilterRulesService.Remove.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun remove(request: RemoveFilterRuleRequest): RemoveFilterRuleResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerFilterRulesService.Remove is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerFilterRulesServiceGrpc.getCreateMethod(),
      implementation = ::create
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerFilterRulesServiceGrpc.getListMethod(),
      implementation = ::list
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerFilterRulesServiceGrpc.getRemoveMethod(),
      implementation = ::remove
    )).build()
  }
}
