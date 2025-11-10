package com.jervis.service.websocket

import com.jervis.domain.websocket.WebSocketChannelTypeEnum
import jakarta.annotation.PreDestroy
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Centralized WebSocket session manager with channel-based routing.
 * Separates debug and notification channels to prevent cross-channel message delivery.
 */
@Service
class WebSocketSessionManager {
    private data class SessionInfo(
        val session: WebSocketSession,
        val channelType: WebSocketChannelTypeEnum,
    )

    private val sessions = ConcurrentHashMap<String, SessionInfo>()

    fun registerSession(
        session: WebSocketSession,
        channelType: WebSocketChannelTypeEnum,
    ): String {
        val sessionId = session.id
        sessions[sessionId] = SessionInfo(session, channelType)
        logger.info { "WebSocket session registered: $sessionId on channel $channelType (total: ${sessions.size})" }
        return sessionId
    }

    fun unregisterSession(sessionId: String) {
        sessions.remove(sessionId)
        logger.info { "WebSocket session unregistered: $sessionId (total: ${sessions.size})" }
    }

    fun broadcastToChannel(
        message: String,
        channelType: WebSocketChannelTypeEnum,
    ) {
        sessions.values
            .filter { it.channelType == channelType && it.session.isOpen }
            .forEach { info ->
                info.session.send(Mono.just(info.session.textMessage(message))).subscribe()
            }
    }

    fun broadcast(message: String) {
        sessions.values
            .filter { it.session.isOpen }
            .forEach { info ->
                info.session.send(Mono.just(info.session.textMessage(message))).subscribe()
            }
    }

    fun sendToSession(
        sessionId: String,
        message: String,
    ) {
        val sessionInfo = sessions[sessionId]
        if (sessionInfo != null && sessionInfo.session.isOpen) {
            sessionInfo.session.send(Mono.just(sessionInfo.session.textMessage(message))).subscribe()
        } else {
            logger.warn { "Session not found or closed: $sessionId" }
        }
    }

    fun getActiveSessionIds(): Set<String> = sessions.keys.toSet()

    fun isSessionActive(sessionId: String): Boolean = sessions[sessionId]?.session?.isOpen == true

    /**
     * Close all active WebSocket sessions to avoid blocking graceful shutdown.
     */
    fun closeAll() {
        val snapshot = sessions.values.toList()
        snapshot.forEach { info ->
            if (info.session.isOpen) {
                runCatching {
                    info.session.close(CloseStatus.GOING_AWAY).subscribe()
                }.onFailure { ex ->
                    logger.warn(ex) { "Failed closing WebSocket session ${info.session.id}" }
                }
            }
        }
        sessions.clear()
        logger.info { "All WebSocket sessions have been closed" }
    }

    @PreDestroy
    fun onShutdown() {
        logger.info { "Application shutdown: closing all WebSocket sessions" }
        closeAll()
    }
}
