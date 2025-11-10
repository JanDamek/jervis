package com.jervis.configuration

import com.jervis.service.websocket.DebugWebSocketHandler
import com.jervis.service.websocket.NotificationsWebSocketHandler
import mu.KotlinLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.server.WebSocketService
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy

private val logger = KotlinLogging.logger {}

@Configuration
class WebSocketConfig {
    @Bean
    fun webSocketService(): WebSocketService {
        logger.info { "Creating WebSocketService with ReactorNettyRequestUpgradeStrategy" }
        return HandshakeWebSocketService(ReactorNettyRequestUpgradeStrategy())
    }

    @Bean
    fun webSocketHandlerAdapter(webSocketService: WebSocketService): WebSocketHandlerAdapter {
        logger.info { "Creating WebSocketHandlerAdapter" }
        return WebSocketHandlerAdapter(webSocketService)
    }

    @Bean
    fun webSocketHandlerMapping(
        notificationsHandler: NotificationsWebSocketHandler,
        debugHandler: DebugWebSocketHandler,
    ): HandlerMapping {
        val urlMap =
            mapOf(
                "/ws/notifications" to notificationsHandler,
                "/ws/debug" to debugHandler,
            )
        logger.info { "Registering WebSocket handlers: ${urlMap.keys}" }

        return SimpleUrlHandlerMapping()
            .apply {
                order = -1
                setUrlMap(urlMap)
            }.also {
                logger.info { "SimpleUrlHandlerMapping created with order: ${it.order}" }
            }
    }
}
