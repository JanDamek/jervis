package com.jervis.service.websocket

import com.jervis.domain.websocket.WebSocketChannelTypeEnum
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

@Component
class DebugWebSocketHandler(
    private val sessionManager: WebSocketSessionManager,
) : WebSocketHandler {
    init {
        logger.info { "DebugWebSocketHandler initialized" }
    }

    override fun handle(session: WebSocketSession): Mono<Void> {
        val sessionId = sessionManager.registerSession(session, WebSocketChannelTypeEnum.DEBUG)
        logger.info { "Debug WebSocket client connected: $sessionId from ${session.handshakeInfo.remoteAddress}" }

        return session
            .receive()
            .doOnNext { logger.debug { "Received message on debug session $sessionId (ignoring - broadcast only)" } }
            .then()
            .doFinally {
                logger.info { "Debug WebSocket client disconnected: $sessionId" }
                sessionManager.unregisterSession(sessionId)
            }
    }
}
