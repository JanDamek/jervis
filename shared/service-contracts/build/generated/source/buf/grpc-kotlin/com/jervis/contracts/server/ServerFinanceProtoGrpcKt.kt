package com.jervis.contracts.server

import com.jervis.contracts.server.ServerFinanceServiceGrpc.getServiceDescriptor
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
 * Holder for Kotlin coroutine-based client and server APIs for jervis.server.ServerFinanceService.
 */
public object ServerFinanceServiceGrpcKt {
  public const val SERVICE_NAME: String = ServerFinanceServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val getSummaryMethod:
      MethodDescriptor<GetFinancialSummaryRequest, GetFinancialSummaryResponse>
    @JvmStatic
    get() = ServerFinanceServiceGrpc.getGetSummaryMethod()

  public val listRecordsMethod:
      MethodDescriptor<ListFinancialRecordsRequest, ListFinancialRecordsResponse>
    @JvmStatic
    get() = ServerFinanceServiceGrpc.getListRecordsMethod()

  public val createRecordMethod:
      MethodDescriptor<CreateFinancialRecordRequest, CreateFinancialRecordResponse>
    @JvmStatic
    get() = ServerFinanceServiceGrpc.getCreateRecordMethod()

  public val listContractsMethod: MethodDescriptor<ListContractsRequest, ListContractsResponse>
    @JvmStatic
    get() = ServerFinanceServiceGrpc.getListContractsMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.server.ServerFinanceService service as suspending coroutines.
   */
  @StubFor(ServerFinanceServiceGrpc::class)
  public class ServerFinanceServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<ServerFinanceServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): ServerFinanceServiceCoroutineStub = ServerFinanceServiceCoroutineStub(channel, callOptions)

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
    public suspend fun getSummary(request: GetFinancialSummaryRequest, headers: Metadata = Metadata()): GetFinancialSummaryResponse = unaryRpc(
      channel,
      ServerFinanceServiceGrpc.getGetSummaryMethod(),
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
    public suspend fun listRecords(request: ListFinancialRecordsRequest, headers: Metadata = Metadata()): ListFinancialRecordsResponse = unaryRpc(
      channel,
      ServerFinanceServiceGrpc.getListRecordsMethod(),
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
    public suspend fun createRecord(request: CreateFinancialRecordRequest, headers: Metadata = Metadata()): CreateFinancialRecordResponse = unaryRpc(
      channel,
      ServerFinanceServiceGrpc.getCreateRecordMethod(),
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
    public suspend fun listContracts(request: ListContractsRequest, headers: Metadata = Metadata()): ListContractsResponse = unaryRpc(
      channel,
      ServerFinanceServiceGrpc.getListContractsMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.server.ServerFinanceService service based on Kotlin coroutines.
   */
  public abstract class ServerFinanceServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.server.ServerFinanceService.GetSummary.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getSummary(request: GetFinancialSummaryRequest): GetFinancialSummaryResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerFinanceService.GetSummary is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerFinanceService.ListRecords.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listRecords(request: ListFinancialRecordsRequest): ListFinancialRecordsResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerFinanceService.ListRecords is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerFinanceService.CreateRecord.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun createRecord(request: CreateFinancialRecordRequest): CreateFinancialRecordResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerFinanceService.CreateRecord is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.server.ServerFinanceService.ListContracts.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listContracts(request: ListContractsRequest): ListContractsResponse = throw StatusException(UNIMPLEMENTED.withDescription("Method jervis.server.ServerFinanceService.ListContracts is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerFinanceServiceGrpc.getGetSummaryMethod(),
      implementation = ::getSummary
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerFinanceServiceGrpc.getListRecordsMethod(),
      implementation = ::listRecords
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerFinanceServiceGrpc.getCreateRecordMethod(),
      implementation = ::createRecord
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = ServerFinanceServiceGrpc.getListContractsMethod(),
      implementation = ::listContracts
    )).build()
  }
}
