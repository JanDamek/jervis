package com.jervis.configuration

import com.jervis.configuration.properties.NettyProperties
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
class NettyServerConfig(private val properties: NettyProperties) {
    @Bean
    fun jervisNettyWebServerCustomizer(): WebServerFactoryCustomizer<NettyReactiveWebServerFactory> =
        WebServerFactoryCustomizer { factory ->
            factory.addServerCustomizers(
                NettyServerCustomizer { httpServer ->
                    httpServer
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeoutMs)
                        .option(ChannelOption.SO_KEEPALIVE, properties.soKeepalive)
                        .idleTimeout(Duration.ofSeconds(properties.idleTimeoutSeconds))
                        .doOnConnection { conn ->
                            conn.addHandlerLast(ReadTimeoutHandler(properties.readTimeoutSeconds, TimeUnit.SECONDS))
                            conn.addHandlerLast(WriteTimeoutHandler(properties.writeTimeoutSeconds, TimeUnit.SECONDS))
                        }
                },
            )
        }
}
