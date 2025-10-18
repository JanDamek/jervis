package com.jervis.config

import com.jervis.service.websocket.DebugWebSocketHandler
import com.jervis.service.websocket.NotificationsWebSocketHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter

@Configuration
class WebSocketConfig {
    @Bean
    fun webSocketMapping(
        notificationsHandler: NotificationsWebSocketHandler,
        debugHandler: DebugWebSocketHandler,
    ): HandlerMapping =
        SimpleUrlHandlerMapping().apply {
            urlMap =
                mapOf(
                    "/ws/notifications" to notificationsHandler,
                    "/ws/debug" to debugHandler,
                )
            order = Ordered.HIGHEST_PRECEDENCE
        }

    @Bean
    fun webSocketHandlerAdapter(): WebSocketHandlerAdapter = WebSocketHandlerAdapter()
}
