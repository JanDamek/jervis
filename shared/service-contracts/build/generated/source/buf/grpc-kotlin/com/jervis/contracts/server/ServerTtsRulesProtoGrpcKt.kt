package com.jervis.contracts.server

import com.jervis.contracts.server.ServerTtsRulesServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerTtsRulesService.
 */
public object ServerTtsRulesServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerTtsRulesServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val getForScopeMethod: MethodDescriptor<GetForScopeRequest, TtsRuleList>
    @JvmStatic
    get() = ServerTtsRulesServiceGrpc.getGetForScopeMethod()

  public val listMethod: MethodDescriptor<ListTtsRulesRequest, TtsRuleList>
    @JvmStatic
    get() = ServerTtsRulesServiceGrpc.getListMethod()

  public val addMethod: MethodDescriptor<TtsRule, TtsRule>
    @JvmStatic
    get() = ServerTtsRulesServiceGrpc.getAddMethod()

  public val updateMethod: MethodDescriptor<TtsRule, TtsRule>
    @JvmStatic
    get() = ServerTtsRulesServiceGrpc.getUpdateMethod()

  public val deleteMethod: MethodDescriptor<DeleteTtsRuleRequest, DeleteTtsRuleResponse>
    @JvmStatic
    get() = ServerTtsRulesServiceGrpc.getDeleteMethod()

  public val previewMethod: MethodDescriptor<PreviewRequest, PreviewResponse>
    @JvmStatic
    get() = ServerTtsRulesServiceGrpc.getPreviewMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerTtsRulesService service as suspending coroutines.
   */
  @StubFor(ServerTtsRulesServiceGrpc::class)
  public class ServerTtsRulesServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerTtsRulesServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerTtsRulesServiceCoroutineStub = ServerTtsRulesServiceCoroutineStub(channel, callOptions)

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
    public suspend fun getForScope(request: GetForScopeRequest, headers: Metadata = Metadata()): TtsRuleList = unaryRpc(
      channel,
      ServerTtsRulesServiceGrpc.getGetForScopeMethod(),
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
    public suspend fun list(request: ListTtsRulesRequest, headers: Metadata = Metadata()): TtsRuleList = unaryRpc(
      channel,
      ServerTtsRulesServiceGrpc.getListMethod(),
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
    public suspend fun add(request: TtsRule, headers: Metadata = Metadata()): TtsRule = unaryRpc(
      channel,
      ServerTtsRulesServiceGrpc.getAddMethod(),
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
    public suspend fun update(request: TtsRule, headers: Metadata = Metadata()): TtsRule = unaryRpc(
      channel,
      ServerTtsRulesServiceGrpc.getUpdateMethod(),
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
    public suspend fun delete(request: DeleteTtsRuleRequest, headers: Metadata = Metadata()): DeleteTtsRuleResponse = unaryRpc(
      channel,
      ServerTtsRulesServiceGrpc.getDeleteMethod(),
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
    public suspend fun preview(request: PreviewRequest, headers: Metadata = Metadata()): PreviewResponse = unaryRpc(
      channel,
      ServerTtsRulesServiceGrpc.getPreviewMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerTtsRulesService service based on Kotlin coroutines.
   */
  public abstract class ServerTtsRulesServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerTtsRulesService.GetForScope.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getForScope(request: GetForScopeRequest): TtsRuleList = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTtsRulesService.GetForScope is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTtsRulesService.List.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun list(request: ListTtsRulesRequest): TtsRuleList = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTtsRulesService.List is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTtsRulesService.Add.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun add(request: TtsRule): TtsRule = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTtsRulesService.Add is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTtsRulesService.Update.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun update(request: TtsRule): TtsRule = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTtsRulesService.Update is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTtsRulesService.Delete.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun delete(request: DeleteTtsRuleRequest): DeleteTtsRuleResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTtsRulesService.Delete is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerTtsRulesService.Preview.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun preview(request: PreviewRequest): PreviewResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerTtsRulesService.Preview is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTtsRulesServiceGrpc.getGetForScopeMethod(),
      implementation = ::getForScope
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTtsRulesServiceGrpc.getListMethod(),
      implementation = ::list
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTtsRulesServiceGrpc.getAddMethod(),
      implementation = ::add
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTtsRulesServiceGrpc.getUpdateMethod(),
      implementation = ::update
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTtsRulesServiceGrpc.getDeleteMethod(),
      implementation = ::delete
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerTtsRulesServiceGrpc.getPreviewMethod(),
      implementation = ::preview
    )).build()
  }
}
