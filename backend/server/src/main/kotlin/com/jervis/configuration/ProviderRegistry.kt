package com.jervis.configuration

import com.jervis.common.client.IProviderService
import com.jervis.configuration.properties.EndpointProperties
import com.jervis.dto.connection.ProviderDescriptor
import com.jervis.dto.connection.ProviderEnum
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.http.encodedPath
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.rpc.withService
import kotlinx.serialization.ExperimentalSerializationApi
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

@Service
@OptIn(ExperimentalSerializationApi::class)
class ProviderRegistry(
    private val endpoints: EndpointProperties,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val clients = ConcurrentHashMap<ProviderEnum, IProviderService>()
    private val descriptors = ConcurrentHashMap<ProviderEnum, ProviderDescriptor>()

    @PostConstruct
    fun initialize() {
        scope.launch {
            for ((providerName, baseUrl) in endpoints.providers) {
                try {
                    val provider = ProviderEnum.valueOf(providerName.uppercase())
                    val client = createRpcClient<IProviderService>(baseUrl)
                    val descriptor = client.getDescriptor()
                    clients[provider] = client
                    descriptors[provider] = descriptor
                    logger.info { "Registered remote provider: ${descriptor.displayName} (${descriptor.capabilities})" }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to register provider $providerName at $baseUrl" }
                }
            }
        }
    }

    fun registerLocal(provider: ProviderEnum, service: IProviderService, descriptor: ProviderDescriptor) {
        clients[provider] = service
        descriptors[provider] = descriptor
        logger.info { "Registered local provider: ${descriptor.displayName} (${descriptor.capabilities})" }
    }

    fun getClient(provider: ProviderEnum): IProviderService =
        clients[provider] ?: throw IllegalStateException("Provider $provider not registered. Available: ${clients.keys}")

    fun getClientOrNull(provider: ProviderEnum): IProviderService? = clients[provider]

    suspend fun <T> withClient(provider: ProviderEnum, block: suspend (IProviderService) -> T): T {
        val client = getClient(provider)
        return try {
            block(client)
        } catch (e: IllegalStateException) {
            if ("cancelled" in (e.message ?: "").lowercase()) {
                logger.warn { "RpcClient cancelled for $provider, reconnecting" }
                reconnect(provider)
                block(getClient(provider))
            } else throw e
        }
    }

    fun getDescriptor(provider: ProviderEnum): ProviderDescriptor =
        descriptors[provider] ?: throw IllegalStateException("Provider $provider not registered. Available: ${descriptors.keys}")

    fun getDescriptorOrNull(provider: ProviderEnum): ProviderDescriptor? = descriptors[provider]

    fun getAllDescriptors(): Map<ProviderEnum, ProviderDescriptor> = descriptors.toMap()

    fun isAvailable(provider: ProviderEnum): Boolean = clients.containsKey(provider)

    suspend fun reconnect(provider: ProviderEnum) {
        val baseUrl = endpoints.providers[provider.name.lowercase()]
            ?: throw IllegalStateException("No endpoint configured for $provider")
        val client = createRpcClient<IProviderService>(baseUrl)
        val descriptor = client.getDescriptor()
        clients[provider] = client
        descriptors[provider] = descriptor
        logger.info { "Reconnected provider: ${descriptor.displayName}" }
    }

    private inline fun <@Rpc reified T : Any> createRpcClient(baseUrl: String): T {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val url = io.ktor.http.Url(cleanBaseUrl)
        return HttpClient(CIO) {
            install(WebSockets) {
                pingInterval = 20.seconds
            }
            installKrpc {
                serialization {
                    cbor()
                }
            }
        }.rpc {
            url {
                protocol = url.protocol
                host = url.host
                port = if (url.specifiedPort == 0) url.protocol.defaultPort else url.specifiedPort
                encodedPath = "rpc"
            }
        }.withService<T>()
    }
}
