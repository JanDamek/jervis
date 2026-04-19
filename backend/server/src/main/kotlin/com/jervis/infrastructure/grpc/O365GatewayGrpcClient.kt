package com.jervis.infrastructure.grpc

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.o365_gateway.CalendarEvent
import com.jervis.contracts.o365_gateway.Channel
import com.jervis.contracts.o365_gateway.ChatMessage
import com.jervis.contracts.o365_gateway.ChatSummary
import com.jervis.contracts.o365_gateway.ListCalendarEventsRequest
import com.jervis.contracts.o365_gateway.ListChannelsRequest
import com.jervis.contracts.o365_gateway.ListChatsRequest
import com.jervis.contracts.o365_gateway.ListTeamsRequest
import com.jervis.contracts.o365_gateway.O365GatewayServiceGrpcKt
import com.jervis.contracts.o365_gateway.ReadChannelRequest
import com.jervis.contracts.o365_gateway.ReadChatRequest
import com.jervis.contracts.o365_gateway.Team
import com.jervis.infrastructure.grpc.GrpcChannels.Companion.O365_GATEWAY_CHANNEL
import io.grpc.ManagedChannel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

// Thin Kotlin client around O365GatewayService. Server-side Kotlin
// pollers (Teams chats + channels) and ConnectionRpcImpl browse these
// RPCs instead of the retired `/api/o365/*` Ktor routes.
@Component
class O365GatewayGrpcClient(
    @Qualifier(O365_GATEWAY_CHANNEL) channel: ManagedChannel,
) {
    private val stub = O365GatewayServiceGrpcKt.O365GatewayServiceCoroutineStub(channel)

    private fun ctx(): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun listChats(clientId: String, top: Int = 20): List<ChatSummary> =
        stub.listChats(
            ListChatsRequest.newBuilder()
                .setCtx(ctx()).setClientId(clientId).setTop(top).build(),
        ).chatsList

    suspend fun readChat(clientId: String, chatId: String, top: Int = 20): List<ChatMessage> =
        stub.readChat(
            ReadChatRequest.newBuilder()
                .setCtx(ctx()).setClientId(clientId).setChatId(chatId).setTop(top).build(),
        ).messagesList

    suspend fun listTeams(clientId: String): List<Team> =
        stub.listTeams(
            ListTeamsRequest.newBuilder().setCtx(ctx()).setClientId(clientId).build(),
        ).teamsList

    suspend fun listChannels(clientId: String, teamId: String): List<Channel> =
        stub.listChannels(
            ListChannelsRequest.newBuilder()
                .setCtx(ctx()).setClientId(clientId).setTeamId(teamId).build(),
        ).channelsList

    suspend fun readChannel(
        clientId: String, teamId: String, channelId: String, top: Int = 20,
    ): List<ChatMessage> =
        stub.readChannel(
            ReadChannelRequest.newBuilder()
                .setCtx(ctx())
                .setClientId(clientId)
                .setTeamId(teamId)
                .setChannelId(channelId)
                .setTop(top)
                .build(),
        ).messagesList

    suspend fun listCalendarEvents(
        clientId: String,
        top: Int = 100,
        startDateTime: String = "",
        endDateTime: String = "",
    ): List<CalendarEvent> =
        stub.listCalendarEvents(
            ListCalendarEventsRequest.newBuilder()
                .setCtx(ctx())
                .setClientId(clientId)
                .setTop(top)
                .setStartDateTime(startDateTime)
                .setEndDateTime(endDateTime)
                .build(),
        ).eventsList
}
