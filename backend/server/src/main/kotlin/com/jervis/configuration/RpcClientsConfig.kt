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
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.rpc.withService
import kotlinx.serialization.ExperimentalSerializationApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.time.Duration.Companion.seconds

@Configuration
@OptIn(ExperimentalSerializationApi::class)
class RpcClientsConfig(
    private val endpoints: EndpointProperties,
) {
    private var _tikaClient: ITikaClient? = null
    private var _joernClient: IJoernClient? = null
    private var _whisperClient: IWhisperClient? = null
    private var _atlassianClient: IAtlassianClient? = null
    private var _aiderClient: ICodingClient? = null
    private var _codingEngineClient: ICodingClient? = null
    private var _junieClient: ICodingClient? = null

    @Bean
    fun tikaClient(): ITikaClient = object : ITikaClient {
        override suspend fun process(request: com.jervis.common.dto.TikaProcessRequest) = 
            getTika().process(request)
    }

    @Bean
    fun joernClient(): IJoernClient = object : IJoernClient {
        override suspend fun run(request: com.jervis.common.dto.JoernQueryDto) = 
            getJoern().run(request)
    }

    @Bean
    fun whisperClient(): IWhisperClient = object : IWhisperClient {
        override suspend fun transcribe(request: com.jervis.common.dto.WhisperRequestDto) = 
            getWhisper().transcribe(request)
    }

    @Bean
    fun atlassianClient(): IAtlassianClient = object : IAtlassianClient {
        override suspend fun getMyself(request: com.jervis.common.dto.atlassian.AtlassianMyselfRequest) = getAtlassian().getMyself(request)
        override suspend fun searchConfluencePages(request: com.jervis.common.dto.atlassian.ConfluenceSearchRequest) = getAtlassian().searchConfluencePages(request)
        override suspend fun getConfluencePage(request: com.jervis.common.dto.atlassian.ConfluencePageRequest) = getAtlassian().getConfluencePage(request)
        override suspend fun searchJiraIssues(request: com.jervis.common.dto.atlassian.JiraSearchRequest) = getAtlassian().searchJiraIssues(request)
        override suspend fun getJiraIssue(request: com.jervis.common.dto.atlassian.JiraIssueRequest) = getAtlassian().getJiraIssue(request)
        override suspend fun downloadJiraAttachment(request: com.jervis.common.dto.atlassian.JiraAttachmentDownloadRequest) = getAtlassian().downloadJiraAttachment(request)
        override suspend fun downloadConfluenceAttachment(request: com.jervis.common.dto.atlassian.ConfluenceAttachmentDownloadRequest) = getAtlassian().downloadConfluenceAttachment(request)
    }

    @Bean
    fun aiderClient(): ICodingClient = object : ICodingClient {
        override suspend fun execute(request: com.jervis.common.client.CodingRequest) = 
            getAider().execute(request)
    }

    @Bean
    fun codingEngineClient(): ICodingClient = object : ICodingClient {
        override suspend fun execute(request: com.jervis.common.client.CodingRequest) = 
            getCodingEngine().execute(request)
    }

    @Bean
    fun junieClient(): ICodingClient = object : ICodingClient {
        override suspend fun execute(request: com.jervis.common.client.CodingRequest) = 
            getJunie().execute(request)
    }

    @Bean
    fun rpcReconnectHandler(): RpcReconnectHandler = object : RpcReconnectHandler {
        override suspend fun reconnectTika() { _tikaClient = createRpcClient(endpoints.tika.baseUrl) }
        override suspend fun reconnectJoern() { _joernClient = createRpcClient(endpoints.joern.baseUrl) }
        override suspend fun reconnectWhisper() { _whisperClient = createRpcClient(endpoints.whisper.baseUrl) }
        override suspend fun reconnectAtlassian() { _atlassianClient = createRpcClient(endpoints.atlassian.baseUrl) }
        override suspend fun reconnectAider() { _aiderClient = createRpcClient(endpoints.aider.baseUrl) }
        override suspend fun reconnectCodingEngine() { _codingEngineClient = createRpcClient(endpoints.coding.baseUrl) }
        override suspend fun reconnectJunie() { _junieClient = createRpcClient(endpoints.junie.baseUrl) }
    }

    private fun getTika(): ITikaClient = _tikaClient ?: synchronized(this) {
        _tikaClient ?: createRpcClient<ITikaClient>(endpoints.tika.baseUrl).also { _tikaClient = it }
    }

    private fun getJoern(): IJoernClient = _joernClient ?: synchronized(this) {
        _joernClient ?: createRpcClient<IJoernClient>(endpoints.joern.baseUrl).also { _joernClient = it }
    }

    private fun getWhisper(): IWhisperClient = _whisperClient ?: synchronized(this) {
        _whisperClient ?: createRpcClient<IWhisperClient>(endpoints.whisper.baseUrl).also { _whisperClient = it }
    }

    private fun getAtlassian(): IAtlassianClient = _atlassianClient ?: synchronized(this) {
        _atlassianClient ?: createRpcClient<IAtlassianClient>(endpoints.atlassian.baseUrl).also { _atlassianClient = it }
    }

    private fun getAider(): ICodingClient = _aiderClient ?: synchronized(this) {
        _aiderClient ?: createRpcClient<ICodingClient>(endpoints.aider.baseUrl).also { _aiderClient = it }
    }

    private fun getCodingEngine(): ICodingClient = _codingEngineClient ?: synchronized(this) {
        _codingEngineClient ?: createRpcClient<ICodingClient>(endpoints.coding.baseUrl).also { _codingEngineClient = it }
    }

    private fun getJunie(): ICodingClient = _junieClient ?: synchronized(this) {
        _junieClient ?: createRpcClient<ICodingClient>(endpoints.junie.baseUrl).also { _junieClient = it }
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
