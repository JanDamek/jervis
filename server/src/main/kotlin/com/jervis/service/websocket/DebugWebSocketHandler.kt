package com.jervis.service.websocket

import com.jervis.service.debug.DebugService
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono

@Component
class DebugWebSocketHandler(
    private val debugService: DebugService,
) : WebSocketHandler {
    override fun handle(session: WebSocketSession): Mono<Void> {
        debugService.addSession(session)
        return session
            .receive()
            .then()
            .doFinally {
                debugService.removeSession(session)
            }
    }
}
