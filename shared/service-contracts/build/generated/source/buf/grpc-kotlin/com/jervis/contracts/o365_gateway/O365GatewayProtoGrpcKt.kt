package com.jervis.contracts.o365_gateway

import com.jervis.contracts.o365_gateway.O365GatewayServiceGrpc.getServiceDescriptor
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
 * jervis.o365_gateway.O365GatewayService.
 */
public object O365GatewayServiceGrpcKt {
  public const val SERVICE_NAME: String = O365GatewayServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val requestMethod: MethodDescriptor<O365Request, O365Response>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getRequestMethod()

  public val requestBytesMethod: MethodDescriptor<O365Request, O365BytesResponse>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getRequestBytesMethod()

  public val listChatsMethod: MethodDescriptor<ListChatsRequest, ListChatsResponse>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getListChatsMethod()

  public val readChatMethod: MethodDescriptor<ReadChatRequest, ListChatMessagesResponse>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getReadChatMethod()

  public val sendChatMessageMethod: MethodDescriptor<SendChatMessageRequest, ChatMessage>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getSendChatMessageMethod()

  public val listTeamsMethod: MethodDescriptor<ListTeamsRequest, ListTeamsResponse>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getListTeamsMethod()

  public val listChannelsMethod: MethodDescriptor<ListChannelsRequest, ListChannelsResponse>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getListChannelsMethod()

  public val readChannelMethod: MethodDescriptor<ReadChannelRequest, ListChannelMessagesResponse>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getReadChannelMethod()

  public val sendChannelMessageMethod: MethodDescriptor<SendChannelMessageRequest, ChatMessage>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getSendChannelMessageMethod()

  public val listMailMethod: MethodDescriptor<ListMailRequest, ListMailResponse>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getListMailMethod()

  public val readMailMethod: MethodDescriptor<ReadMailRequest, MailMessage>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getReadMailMethod()

  public val sendMailMethod: MethodDescriptor<SendMailRpcRequest, SendMailAck>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getSendMailMethod()

  public val listCalendarEventsMethod:
      MethodDescriptor<ListCalendarEventsRequest, ListCalendarEventsResponse>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getListCalendarEventsMethod()

  public val createCalendarEventMethod: MethodDescriptor<CreateCalendarEventRequest, CalendarEvent>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getCreateCalendarEventMethod()

  public val getOnlineMeetingByJoinUrlMethod:
      MethodDescriptor<OnlineMeetingByJoinUrlRequest, OnlineMeeting>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getGetOnlineMeetingByJoinUrlMethod()

  public val getOnlineMeetingMethod: MethodDescriptor<OnlineMeetingRequest, OnlineMeeting>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getGetOnlineMeetingMethod()

  public val listMeetingRecordingsMethod:
      MethodDescriptor<OnlineMeetingRequest, ListRecordingsResponse>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getListMeetingRecordingsMethod()

  public val listMeetingTranscriptsMethod:
      MethodDescriptor<OnlineMeetingRequest, ListTranscriptsResponse>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getListMeetingTranscriptsMethod()

  public val downloadTranscriptVttMethod: MethodDescriptor<TranscriptRef, TranscriptContent>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getDownloadTranscriptVttMethod()

  public val listDriveItemsMethod: MethodDescriptor<ListDriveItemsRequest, ListDriveItemsResponse>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getListDriveItemsMethod()

  public val getDriveItemMethod: MethodDescriptor<DriveItemRequest, DriveItem>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getGetDriveItemMethod()

  public val searchDriveMethod: MethodDescriptor<SearchDriveRequest, ListDriveItemsResponse>
    @JvmStatic
    get() = O365GatewayServiceGrpc.getSearchDriveMethod()

  /**
   * A stub for issuing RPCs to a(n) jervis.o365_gateway.O365GatewayService service as suspending
   * coroutines.
   */
  @StubFor(O365GatewayServiceGrpc::class)
  public class O365GatewayServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<O365GatewayServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): O365GatewayServiceCoroutineStub
        = O365GatewayServiceCoroutineStub(channel, callOptions)

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
    public suspend fun request(request: O365Request, headers: Metadata = Metadata()): O365Response =
        unaryRpc(
      channel,
      O365GatewayServiceGrpc.getRequestMethod(),
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
    public suspend fun requestBytes(request: O365Request, headers: Metadata = Metadata()):
        O365BytesResponse = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getRequestBytesMethod(),
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
    public suspend fun listChats(request: ListChatsRequest, headers: Metadata = Metadata()):
        ListChatsResponse = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getListChatsMethod(),
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
    public suspend fun readChat(request: ReadChatRequest, headers: Metadata = Metadata()):
        ListChatMessagesResponse = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getReadChatMethod(),
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
    public suspend fun sendChatMessage(request: SendChatMessageRequest, headers: Metadata =
        Metadata()): ChatMessage = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getSendChatMessageMethod(),
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
    public suspend fun listTeams(request: ListTeamsRequest, headers: Metadata = Metadata()):
        ListTeamsResponse = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getListTeamsMethod(),
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
    public suspend fun listChannels(request: ListChannelsRequest, headers: Metadata = Metadata()):
        ListChannelsResponse = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getListChannelsMethod(),
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
    public suspend fun readChannel(request: ReadChannelRequest, headers: Metadata = Metadata()):
        ListChannelMessagesResponse = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getReadChannelMethod(),
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
    public suspend fun sendChannelMessage(request: SendChannelMessageRequest, headers: Metadata =
        Metadata()): ChatMessage = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getSendChannelMessageMethod(),
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
    public suspend fun listMail(request: ListMailRequest, headers: Metadata = Metadata()):
        ListMailResponse = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getListMailMethod(),
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
    public suspend fun readMail(request: ReadMailRequest, headers: Metadata = Metadata()):
        MailMessage = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getReadMailMethod(),
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
    public suspend fun sendMail(request: SendMailRpcRequest, headers: Metadata = Metadata()):
        SendMailAck = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getSendMailMethod(),
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
    public suspend fun listCalendarEvents(request: ListCalendarEventsRequest, headers: Metadata =
        Metadata()): ListCalendarEventsResponse = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getListCalendarEventsMethod(),
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
    public suspend fun createCalendarEvent(request: CreateCalendarEventRequest, headers: Metadata =
        Metadata()): CalendarEvent = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getCreateCalendarEventMethod(),
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
    public suspend fun getOnlineMeetingByJoinUrl(request: OnlineMeetingByJoinUrlRequest,
        headers: Metadata = Metadata()): OnlineMeeting = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getGetOnlineMeetingByJoinUrlMethod(),
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
    public suspend fun getOnlineMeeting(request: OnlineMeetingRequest, headers: Metadata =
        Metadata()): OnlineMeeting = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getGetOnlineMeetingMethod(),
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
    public suspend fun listMeetingRecordings(request: OnlineMeetingRequest, headers: Metadata =
        Metadata()): ListRecordingsResponse = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getListMeetingRecordingsMethod(),
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
    public suspend fun listMeetingTranscripts(request: OnlineMeetingRequest, headers: Metadata =
        Metadata()): ListTranscriptsResponse = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getListMeetingTranscriptsMethod(),
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
    public suspend fun downloadTranscriptVtt(request: TranscriptRef, headers: Metadata =
        Metadata()): TranscriptContent = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getDownloadTranscriptVttMethod(),
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
    public suspend fun listDriveItems(request: ListDriveItemsRequest, headers: Metadata =
        Metadata()): ListDriveItemsResponse = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getListDriveItemsMethod(),
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
    public suspend fun getDriveItem(request: DriveItemRequest, headers: Metadata = Metadata()):
        DriveItem = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getGetDriveItemMethod(),
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
    public suspend fun searchDrive(request: SearchDriveRequest, headers: Metadata = Metadata()):
        ListDriveItemsResponse = unaryRpc(
      channel,
      O365GatewayServiceGrpc.getSearchDriveMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the jervis.o365_gateway.O365GatewayService service based on Kotlin
   * coroutines.
   */
  public abstract class O365GatewayServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.Request.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun request(request: O365Request): O365Response = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.Request is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.RequestBytes.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun requestBytes(request: O365Request): O365BytesResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.RequestBytes is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.ListChats.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listChats(request: ListChatsRequest): ListChatsResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.ListChats is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.ReadChat.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun readChat(request: ReadChatRequest): ListChatMessagesResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.ReadChat is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.SendChatMessage.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun sendChatMessage(request: SendChatMessageRequest): ChatMessage = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.SendChatMessage is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.ListTeams.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listTeams(request: ListTeamsRequest): ListTeamsResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.ListTeams is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.ListChannels.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listChannels(request: ListChannelsRequest): ListChannelsResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.ListChannels is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.ReadChannel.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun readChannel(request: ReadChannelRequest): ListChannelMessagesResponse =
        throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.ReadChannel is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.SendChannelMessage.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun sendChannelMessage(request: SendChannelMessageRequest): ChatMessage =
        throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.SendChannelMessage is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.ListMail.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listMail(request: ListMailRequest): ListMailResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.ListMail is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.ReadMail.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun readMail(request: ReadMailRequest): MailMessage = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.ReadMail is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.SendMail.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun sendMail(request: SendMailRpcRequest): SendMailAck = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.SendMail is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.ListCalendarEvents.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listCalendarEvents(request: ListCalendarEventsRequest):
        ListCalendarEventsResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.ListCalendarEvents is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.o365_gateway.O365GatewayService.CreateCalendarEvent.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun createCalendarEvent(request: CreateCalendarEventRequest): CalendarEvent
        = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.CreateCalendarEvent is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.o365_gateway.O365GatewayService.GetOnlineMeetingByJoinUrl.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getOnlineMeetingByJoinUrl(request: OnlineMeetingByJoinUrlRequest):
        OnlineMeeting = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.GetOnlineMeetingByJoinUrl is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.GetOnlineMeeting.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getOnlineMeeting(request: OnlineMeetingRequest): OnlineMeeting = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.GetOnlineMeeting is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.o365_gateway.O365GatewayService.ListMeetingRecordings.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listMeetingRecordings(request: OnlineMeetingRequest):
        ListRecordingsResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.ListMeetingRecordings is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.o365_gateway.O365GatewayService.ListMeetingTranscripts.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listMeetingTranscripts(request: OnlineMeetingRequest):
        ListTranscriptsResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.ListMeetingTranscripts is unimplemented"))

    /**
     * Returns the response to an RPC for
     * jervis.o365_gateway.O365GatewayService.DownloadTranscriptVtt.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun downloadTranscriptVtt(request: TranscriptRef): TranscriptContent = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.DownloadTranscriptVtt is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.ListDriveItems.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listDriveItems(request: ListDriveItemsRequest): ListDriveItemsResponse =
        throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.ListDriveItems is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.GetDriveItem.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getDriveItem(request: DriveItemRequest): DriveItem = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.GetDriveItem is unimplemented"))

    /**
     * Returns the response to an RPC for jervis.o365_gateway.O365GatewayService.SearchDrive.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun searchDrive(request: SearchDriveRequest): ListDriveItemsResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method jervis.o365_gateway.O365GatewayService.SearchDrive is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getRequestMethod(),
      implementation = ::request
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getRequestBytesMethod(),
      implementation = ::requestBytes
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getListChatsMethod(),
      implementation = ::listChats
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getReadChatMethod(),
      implementation = ::readChat
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getSendChatMessageMethod(),
      implementation = ::sendChatMessage
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getListTeamsMethod(),
      implementation = ::listTeams
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getListChannelsMethod(),
      implementation = ::listChannels
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getReadChannelMethod(),
      implementation = ::readChannel
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getSendChannelMessageMethod(),
      implementation = ::sendChannelMessage
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getListMailMethod(),
      implementation = ::listMail
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getReadMailMethod(),
      implementation = ::readMail
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getSendMailMethod(),
      implementation = ::sendMail
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getListCalendarEventsMethod(),
      implementation = ::listCalendarEvents
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getCreateCalendarEventMethod(),
      implementation = ::createCalendarEvent
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getGetOnlineMeetingByJoinUrlMethod(),
      implementation = ::getOnlineMeetingByJoinUrl
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getGetOnlineMeetingMethod(),
      implementation = ::getOnlineMeeting
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getListMeetingRecordingsMethod(),
      implementation = ::listMeetingRecordings
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getListMeetingTranscriptsMethod(),
      implementation = ::listMeetingTranscripts
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getDownloadTranscriptVttMethod(),
      implementation = ::downloadTranscriptVtt
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getListDriveItemsMethod(),
      implementation = ::listDriveItems
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getGetDriveItemMethod(),
      implementation = ::getDriveItem
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = O365GatewayServiceGrpc.getSearchDriveMethod(),
      implementation = ::searchDrive
    )).build()
  }
}
