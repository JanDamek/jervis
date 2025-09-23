package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties(prefix = "connection-pools")
class ConnectionPoolProperties {
    var webclient: WebClientConnectionPool = WebClientConnectionPool()

    data class WebClientConnectionPool(
        var maxConnections: Int = 500,
        var maxIdleTimeMinutes: Long = 30,
        var maxLifeTimeMinutes: Long = 60,
        var pendingAcquireTimeoutMinutes: Long = 30,
        var evictInBackgroundMinutes: Long = 60,
        var pendingAcquireMaxCount: Int = 1000,
    )

    fun getWebClientMaxConnections(): Int = webclient.maxConnections

    fun getWebClientMaxIdleTime(): Duration = Duration.ofMinutes(webclient.maxIdleTimeMinutes)

    fun getWebClientMaxLifeTime(): Duration = Duration.ofMinutes(webclient.maxLifeTimeMinutes)

    fun getWebClientPendingAcquireTimeout(): Duration = Duration.ofMinutes(webclient.pendingAcquireTimeoutMinutes)

    fun getWebClientEvictInBackground(): Duration = Duration.ofMinutes(webclient.evictInBackgroundMinutes)
}
