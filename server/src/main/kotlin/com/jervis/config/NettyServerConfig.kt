package com.jervis.config

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory
import org.springframework.boot.web.embedded.netty.NettyServerCustomizer
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.TimeUnit

@Configuration
class NettyServerConfig {
    @Bean
    fun nettyWebServerFactoryCustomizer(): WebServerFactoryCustomizer<NettyReactiveWebServerFactory> =
        WebServerFactoryCustomizer { factory ->
            factory.addServerCustomizers(
                NettyServerCustomizer { httpServer ->
                    httpServer
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 60_000)
                        .option(ChannelOption.SO_KEEPALIVE, true)
                        .idleTimeout(Duration.ofMinutes(10)) // 10 minutes for long-running operations
                        .doOnConnection { conn ->
                            conn.addHandlerLast(ReadTimeoutHandler(600, TimeUnit.SECONDS)) // 10 minutes
                            conn.addHandlerLast(WriteTimeoutHandler(600, TimeUnit.SECONDS)) // 10 minutes
                        }
                },
            )
        }
}
