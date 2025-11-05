package com.jervis.configuration

import com.jervis.client.DebugWebSocketClient
import com.jervis.client.NotificationsWebSocketClient
import com.jervis.config.HttpInterfaceClientConfig
import com.jervis.service.IDebugWindowService
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.util.retry.Retry
import java.time.Duration

@Configuration
@Import(HttpInterfaceClientConfig::class)
class ApiClientConfiguration(
    @Value("\${jervis.server.url}")
    private val serverUrl: String,
) {
    private val logger = KotlinLogging.logger {}

    @Bean
    @OptIn(ExperimentalSerializationApi::class)
    fun json(): Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        }

    @Bean
    fun webClient(json: Json): WebClient =
        WebClient
            .builder()
            .baseUrl(serverUrl)
            // Use default codecs (Jackson) for REST endpoints to ensure compatibility with server responses.
            // Kotlinx is still used explicitly for WebSocket payloads via the Ktor client.
            .filter { request, next ->
                next
                    .exchange(request)
                    .retryWhen(createExponentialBackoffRetry())
                    .doOnError { error ->
                        logger.error(error) { "HTTP request failed after all retries: ${request.method()} ${request.url()}" }
                    }
            }.build()

    private fun createExponentialBackoffRetry(): Retry =
        Retry
            .backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
            .maxBackoff(Duration.ofMinutes(1))
            .filter { throwable ->
                when (throwable) {
                    is WebClientResponseException -> {
                        val status = throwable.statusCode
                        status.is5xxServerError || status == HttpStatus.REQUEST_TIMEOUT || status == HttpStatus.TOO_MANY_REQUESTS
                    }

                    is java.net.ConnectException -> true
                    is java.nio.channels.ClosedChannelException -> true
                    is java.io.IOException -> true

                    else -> false
                }
            }.doBeforeRetry { signal ->
                logger.warn {
                    "Retrying HTTP request (attempt ${signal.totalRetries() + 1}) after ${signal.failure().message}. " +
                        "Next retry in ${signal.totalRetriesInARow()}s"
                }
            }.onRetryExhaustedThrow { _, signal ->
                signal.failure()
            }

    @Bean
    fun notificationsClient(applicationEventPublisher: ApplicationEventPublisher): NotificationsWebSocketClient =
        NotificationsWebSocketClient(serverUrl, applicationEventPublisher).also { it.start() }

    @Bean
    fun errorLogClient(webClient: WebClient): com.jervis.service.IErrorLogService =
        httpConfig.createHttpServiceProxyFactory(webClient).createClient(com.jervis.service.IErrorLogService::class.java)

    @Bean
    fun debugClient(debugWindowService: IDebugWindowService): DebugWebSocketClient =
        DebugWebSocketClient(serverUrl, debugWindowService).also { it.start() }
}
