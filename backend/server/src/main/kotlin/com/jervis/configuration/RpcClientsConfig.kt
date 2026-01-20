package com.jervis.configuration

import com.jervis.common.client.IAtlassianClient
import com.jervis.common.client.ICodingClient
import com.jervis.common.client.IJoernClient
import com.jervis.common.client.ITikaClient
import com.jervis.common.client.IWhisperClient
import com.jervis.configuration.properties.EndpointProperties
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.encodedPath
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.rpc.withService
import kotlinx.serialization.ExperimentalSerializationApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@OptIn(ExperimentalSerializationApi::class)
class RpcClientsConfig(
    private val endpoints: EndpointProperties,
) {
    @Bean
    fun tikaClient(): ITikaClient = createRpcClient<ITikaClient>(endpoints.tika.baseUrl)

    @Bean
    fun joernClient(): IJoernClient = createRpcClient<IJoernClient>(endpoints.joern.baseUrl)

    @Bean
    fun whisperClient(): IWhisperClient = createRpcClient<IWhisperClient>(endpoints.whisper.baseUrl)

    @Bean
    fun atlassianClient(): IAtlassianClient = createRpcClient<IAtlassianClient>(endpoints.atlassian.baseUrl)

    @Bean
    fun aiderClient(): ICodingClient = createRpcClient<ICodingClient>(endpoints.aider.baseUrl)

    @Bean
    fun codingEngineClient(): ICodingClient = createRpcClient<ICodingClient>(endpoints.coding.baseUrl)

    @Bean
    fun junieClient(): ICodingClient = createRpcClient<ICodingClient>(endpoints.junie.baseUrl)

    private inline fun <@Rpc reified T : Any> createRpcClient(baseUrl: String): T {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val url = io.ktor.http.Url(cleanBaseUrl)

        return HttpClient(CIO) {
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
