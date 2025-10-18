package com.jervis.service.websocket

import com.jervis.service.notification.NotificationsPublisher
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono

@Component
class NotificationsWebSocketHandler(
    private val publisher: NotificationsPublisher,
) : WebSocketHandler {
    override fun handle(session: WebSocketSession): Mono<Void> {
        val out = publisher.stream().map { session.textMessage(it) }
        return session.send(out)
    }
}
