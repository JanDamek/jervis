package com.jervis.service.websocket

import com.jervis.domain.websocket.WebSocketChannelTypeEnum
import com.jervis.dto.events.UserDialogCloseEventDto
import com.jervis.dto.events.UserDialogResponseEventDto
import com.jervis.service.dialog.UserDialogCoordinator
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

@Component
class NotificationsWebSocketHandler(
    private val sessionManager: WebSocketSessionManager,
    private val userDialogCoordinator: UserDialogCoordinator,
) : WebSocketHandler {
    init {
        logger.info { "NotificationsWebSocketHandler initialized" }
    }

    override fun handle(session: WebSocketSession): Mono<Void> {
        val sessionId = sessionManager.registerSession(session, WebSocketChannelTypeEnum.NOTIFICATIONS)
        logger.info { "Notifications WebSocket client connected: $sessionId from ${session.handshakeInfo.remoteAddress}" }

        val json = Json { ignoreUnknownKeys = true }

        return session
            .receive()
            .doOnNext { msg ->
                runCatching {
                    val text = msg.payloadAsText
                    // Try parse dialog response
                    runCatching { json.decodeFromString(UserDialogResponseEventDto.serializer(), text) }
                        .onSuccess { dto ->
                            logger.info { "Received USER_DIALOG_RESPONSE for dialogId=${dto.dialogId}" }
                            // Fire and forget suspend call
                            reactor.core.scheduler.Schedulers.boundedElastic().schedule {
                                kotlinx.coroutines.runBlocking {
                                    userDialogCoordinator.handleClientResponse(
                                        dialogId = dto.dialogId,
                                        correlationId = dto.correlationId,
                                        answer = dto.answer,
                                        accepted = dto.accepted,
                                    )
                                }
                            }
                            return@doOnNext
                        }

                    // Try parse dialog close
                    runCatching { json.decodeFromString(UserDialogCloseEventDto.serializer(), text) }
                        .onSuccess { dto ->
                            logger.info { "Received USER_DIALOG_CLOSE for dialogId=${dto.dialogId}" }
                            reactor.core.scheduler.Schedulers.boundedElastic().schedule {
                                kotlinx.coroutines.runBlocking {
                                    userDialogCoordinator.handleClientClose(
                                        dialogId = dto.dialogId,
                                        correlationId = dto.correlationId,
                                    )
                                }
                            }
                            return@doOnNext
                        }
                }.onFailure {
                    logger.warn(it) { "Failed to handle incoming WS message on notifications channel" }
                }
            }.then()
            .doFinally {
                logger.info { "Notifications WebSocket client disconnected: $sessionId" }
                sessionManager.unregisterSession(sessionId)
            }
    }
}
